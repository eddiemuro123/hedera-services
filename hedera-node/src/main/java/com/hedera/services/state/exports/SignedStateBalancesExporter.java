package com.hedera.services.state.exports;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.protobuf.InvalidProtocolBufferException;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hedera.services.ServicesState;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityAssociation;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.utils.MiscUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.AllAccountBalances;
import com.hederahashgraph.api.proto.java.SingleAccountBalances;
import com.hederahashgraph.api.proto.java.TokenBalance;
import com.hederahashgraph.api.proto.java.TokenBalances;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.fcmap.FCMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.Optional;

import static com.hedera.services.state.merkle.MerkleEntityAssociation.fromAccountTokenRel;
import static com.hedera.services.state.merkle.MerkleEntityId.fromTokenId;
import static com.hedera.services.utils.EntityIdUtils.readableId;

public class SignedStateBalancesExporter implements BalancesExporter {
	static Logger log = LogManager.getLogger(SignedStateBalancesExporter.class);

	static final String LINE_SEPARATOR = System.getProperty("line.separator");
	static final String UNKNOWN_EXPORT_DIR = "";
	static final String BAD_EXPORT_DIR_ERROR_MSG_TPL = "Cannot ensure existence of export dir '%s'!";
	static final String LOW_NODE_BALANCE_WARN_MSG_TPL = "Node '%s' has unacceptably low balance %d!";
	static final String BAD_EXPORT_ATTEMPT_ERROR_MSG_TPL = "Could not export to '%s'!";
	static final String BAD_SIGNING_ATTEMPT_ERROR_MSG_TPL = "Could not sign balance file '%s'!";
	static final String GOOD_SIGNING_ATTEMPT_DEBUG_MSG_TPL = "Created balance signature file '%s'.";
	static final String CURRENT_VERSION = "version:2";

	static final Instant NEVER = null;
	static final Base64.Encoder encoder = Base64.getEncoder();

	final long expectedFloat;
	final UnaryOperator<byte[]> signer;
	final GlobalDynamicProperties dynamicProperties;

	SigFileWriter sigFileWriter = new StandardSigFileWriter();
	FileHashReader hashReader = new Sha384HashReader();
	DirectoryAssurance directories = loc -> Files.createDirectories(Paths.get(loc));

	String lastUsedExportDir = UNKNOWN_EXPORT_DIR;
	Instant periodEnd = NEVER;

	public SignedStateBalancesExporter(
			PropertySource properties,
			UnaryOperator<byte[]> signer,
			GlobalDynamicProperties dynamicProperties
	) {
		this.signer = signer;
		this.expectedFloat = properties.getLongProperty("ledger.totalTinyBarFloat");
		this.dynamicProperties = dynamicProperties;
	}

	@Override
	public boolean isTimeToExport(Instant now) {
		if (periodEnd == NEVER) {
			periodEnd = now.plusSeconds(dynamicProperties.balancesExportPeriodSecs());
		} else {
			if (now.isAfter(periodEnd)) {
				log.info("balancesExportPeriodSecs: {} ", dynamicProperties.balancesExportPeriodSecs());
				periodEnd = now.plusSeconds(dynamicProperties.balancesExportPeriodSecs());
				return true;
			}
		}
		return false;
	}

	@Override
	public void toCsvFile(ServicesState signedState, Instant when) {
		if (!ensureExportDir(signedState.getNodeAccountId())) {
			return;
		}
		var summary = summarized(signedState);
		var expected = BigInteger.valueOf(expectedFloat);
		if (!expected.equals(summary.getTotalFloat())) {
			throw new IllegalStateException(String.format(
					"Signed state @ %s had total balance %d not %d!",
					when,
					summary.getTotalFloat(),
					expectedFloat));
		}
		var csvLoc = lastUsedExportDir + when.toString().replace(":", "_") + "_Balances.csv";
		boolean exportSucceeded = exportBalancesFile(summary, csvLoc, when);
		if (exportSucceeded) {
			tryToSign(csvLoc);
		}
	}

	// TODO: add the log info to tell how long it take to export and sign the proto account balances file
	//       as some validation info
	//       Do the same to csv file.
	@Override
	public void toProtoFile(ServicesState signedState, Instant when) {
		if (!ensureExportDir(signedState.getNodeAccountId())) {
			return;
		}

		AllAccountBalances.Builder allAccountBalancesBuilder = AllAccountBalances.newBuilder();

		var expected = BigInteger.valueOf(expectedFloat);
		var total = calcTotalAndBuildProtoMessage(signedState, when, allAccountBalancesBuilder);

		if (!expected.equals(total)) {
			throw new IllegalStateException(String.format(
					"Signed state @ %s had total balance %d not %d!",
					when,total,	expectedFloat));
		}

		var protoLoc = lastUsedExportDir + when.toString().replace(":", "_") + "_Balances.proto";
		boolean exportSucceeded = exportBalancesProtoFile(allAccountBalancesBuilder, protoLoc);

		if (exportSucceeded) {
			tryToSign(protoLoc);
		}
	}

	private void tryToSign(String csvLoc) {
		try {
			var hash = hashReader.readHash(csvLoc);
			var sig = signer.apply(hash);
			var sigFileLoc = sigFileWriter.writeSigFile(csvLoc, sig, hash);
			if (log.isDebugEnabled()) {
				log.debug(String.format(GOOD_SIGNING_ATTEMPT_DEBUG_MSG_TPL, sigFileLoc));
			}
		} catch (Exception e) {
			log.error(String.format(BAD_SIGNING_ATTEMPT_ERROR_MSG_TPL, csvLoc), e);
		}
	}

	private boolean exportBalancesFile(BalancesSummary summary, String csvLoc, Instant when) {
		try (BufferedWriter fout = Files.newBufferedWriter(Paths.get(csvLoc))) {
			if (dynamicProperties.shouldExportTokenBalances()) {
				addRelease090Header(fout, when);
			} else {
				addLegacyHeader(fout, when);
			}
			for (AccountBalance entry : summary.getOrderedBalances())  {
				fout.write(String.format(
						"%d,%d,%d,%d",
						entry.getShard(),
						entry.getRealm(),
						entry.getNum(),
						entry.getBalance()));
				if (dynamicProperties.shouldExportTokenBalances()) {
					fout.write("," + entry.getB64TokenBalances());
				}
				fout.write(LINE_SEPARATOR);
			}
		} catch (IOException e) {
			log.error(String.format(BAD_EXPORT_ATTEMPT_ERROR_MSG_TPL, csvLoc), e);
			return false;
		}
		return true;
	}

	private BigInteger calcTotalAndBuildProtoMessage(ServicesState signedState, Instant when,
			AllAccountBalances.Builder allAccountBalancesBuilder) {

		long nodeBalanceWarnThreshold = dynamicProperties.nodeBalanceWarningThreshold();
		BigInteger totalFloat = BigInteger.valueOf(0L);

		var nodeIds = MiscUtils.getNodeAccounts(signedState.addressBook());
		var tokens = signedState.tokens();
		var accounts = signedState.accounts();
		var tokenAssociations = signedState.tokenAssociations();

		for (MerkleEntityId id : accounts.keySet()) {
			var account = accounts.get(id);
			if (!account.isDeleted()) {
				var accountId = id.toAccountId();
				var balance = account.getBalance();
				if (nodeIds.contains(accountId) && balance < nodeBalanceWarnThreshold) {
					log.warn(String.format(
							LOW_NODE_BALANCE_WARN_MSG_TPL,
							readableId(accountId),
							balance));
				}
				totalFloat = totalFloat.add(BigInteger.valueOf(account.getBalance()));

				SingleAccountBalances.Builder singleAccountBuilder =SingleAccountBalances.newBuilder();
				singleAccountBuilder.setAccountID(accountId)
						.setHbarBalance(balance);

				if (dynamicProperties.shouldExportTokenBalances()) {
					var accountTokens = account.tokens();
					if (accountTokens.numAssociations() > 0) {
						for (TokenID tokenId : accountTokens.asIds()) {
							var token = tokens.get(fromTokenId(tokenId));
							if (token != null && !token.isDeleted()) {
								var relationship = tokenAssociations.get(fromAccountTokenRel(accountId, tokenId));
								singleAccountBuilder.addTokenBalances(tb(tokenId, relationship.getBalance()));
							}
						}
					}
				}
				Timestamp.Builder consensusTimeStamp = Timestamp.newBuilder();
				consensusTimeStamp.setSeconds(when.getEpochSecond()).setNanos(when.getNano());
				allAccountBalancesBuilder.setConsensusTimestamp(consensusTimeStamp.build());
				allAccountBalancesBuilder.addAllAccounts(singleAccountBuilder.build());
			}
		}
		return totalFloat;
	}

	private boolean exportBalancesProtoFile(AllAccountBalances.Builder allAccountsBuilder, String protoLoc) {
		if(log.isDebugEnabled()) {
			log.debug("Export all accounts to protobuf file {} ", protoLoc);
		}

		try (FileOutputStream fout = new FileOutputStream(protoLoc)) {
			allAccountsBuilder.build().writeTo(fout);
		} catch (IOException e) {
			log.error(String.format(BAD_EXPORT_ATTEMPT_ERROR_MSG_TPL, protoLoc), e);
			return false;
		}
		return true;
	}

	public Optional<AllAccountBalances> importBalanceProtoFile(String protoLoc) {
		try {
			FileInputStream fin = new FileInputStream(protoLoc);
			AllAccountBalances allAccountBalances = AllAccountBalances.parseFrom(fin);
			return Optional.ofNullable(allAccountBalances);
		} catch (InvalidProtocolBufferException e) {
			log.error("protobuf message file is corrupted: {}", protoLoc);
		} catch (IOException e) {
			log.error("Can't read protobuf message file {}", protoLoc);
		}
		return Optional.empty();
	}

	private void addLegacyHeader(Writer writer, Instant at) throws IOException {
		writer.write(String.format("TimeStamp:%s%s", at, LINE_SEPARATOR));
		writer.write("shardNum,realmNum,accountNum,balance" + LINE_SEPARATOR);
	}

	private void addRelease090Header(Writer writer, Instant at) throws IOException {
		writer.write("# " + CURRENT_VERSION + LINE_SEPARATOR);
		writer.write(String.format("# TimeStamp:%s%s", at, LINE_SEPARATOR));
		writer.write("shardNum,realmNum,accountNum,balance,tokenBalances" + LINE_SEPARATOR);
	}

	BalancesSummary summarized(ServicesState signedState) {
		long nodeBalanceWarnThreshold = dynamicProperties.nodeBalanceWarningThreshold();
		BigInteger totalFloat = BigInteger.valueOf(0L);
		List<AccountBalance> accountBalances = new ArrayList<>();

		var nodeIds = MiscUtils.getNodeAccounts(signedState.addressBook());
		var tokens = signedState.tokens();
		var accounts = signedState.accounts();
		var tokenAssociations = signedState.tokenAssociations();
		for (MerkleEntityId id : accounts.keySet())	{
			var account = accounts.get(id);
			if (!account.isDeleted()) {
				var accountId = id.toAccountId();
				var balance = account.getBalance();
				if (nodeIds.contains(accountId) && balance < nodeBalanceWarnThreshold) {
					log.warn(String.format(
							LOW_NODE_BALANCE_WARN_MSG_TPL,
							readableId(accountId),
							balance));
				}
				totalFloat = totalFloat.add(BigInteger.valueOf(account.getBalance()));
				var balancesEntry = new AccountBalance(id.getShard(), id.getRealm(), id.getNum(), account.getBalance());
				if (dynamicProperties.shouldExportTokenBalances()) {
					addTokenBalances(accountId, account, balancesEntry, tokens, tokenAssociations);
				}
				accountBalances.add(balancesEntry);
			}
		}
		Collections.sort(accountBalances);

		return new BalancesSummary(totalFloat, accountBalances);
	}

	private void addTokenBalances(
			AccountID id,
			MerkleAccount account,
			AccountBalance balancesEntry,
			FCMap<MerkleEntityId, MerkleToken> tokens,
			FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenAssociations
	) {
		var accountTokens = account.tokens();
		if (accountTokens.numAssociations() > 0) {
			var tokenBalances = TokenBalances.newBuilder();
			for (TokenID tokenId : accountTokens.asIds()) {
				var token = tokens.get(fromTokenId(tokenId));
				if (token != null && !token.isDeleted()) {
					var relationship = tokenAssociations.get(fromAccountTokenRel(id, tokenId));
					tokenBalances.addTokenBalances(tb(tokenId, relationship.getBalance()));
				}
			}
			if (tokenBalances.getTokenBalancesCount() > 0) {
				balancesEntry.setB64TokenBalances(b64Encode(tokenBalances.build()));
			}
		}
	}

	private TokenBalance tb(TokenID id, long balance) {
		return TokenBalance.newBuilder().setTokenId(id).setBalance(balance).build();
	}

	static String b64Encode(TokenBalances tokenBalances) {
		return encoder.encodeToString(tokenBalances.toByteArray());
	}

	private boolean ensureExportDir(AccountID node) {
		var correctDir = dynamicProperties.pathToBalancesExportDir();
		if (!lastUsedExportDir.startsWith(correctDir)) {
			var sb = new StringBuilder(correctDir);
			if (!correctDir.endsWith(File.separator)) {
				sb.append(File.separator);
			}
			sb.append("balance").append(readableId(node)).append(File.separator);
			var candidateDir = sb.toString();
			try {
				directories.ensureExistenceOf(candidateDir);
				lastUsedExportDir = candidateDir;
			} catch (IOException e) {
				log.error(String.format(BAD_EXPORT_DIR_ERROR_MSG_TPL, candidateDir));
				return false;
			}
		}
		return true;
	}

	static class BalancesSummary {
		private final BigInteger totalFloat;
		private final List<AccountBalance> orderedBalances;

		BalancesSummary(
				BigInteger totalFloat,
				List<AccountBalance> orderedBalances
		) {
			this.totalFloat = totalFloat;
			this.orderedBalances = orderedBalances;
		}

		public BigInteger getTotalFloat() {
			return totalFloat;
		}

		public List<AccountBalance> getOrderedBalances() {
			return orderedBalances;
		}
	}
}

