package com.hedera.services.bdd.spec.transactions;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.hedera.services.bdd.spec.transactions.consensus.HapiMessageSubmit;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicCreate;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicDelete;
import com.hedera.services.bdd.spec.transactions.consensus.HapiTopicUpdate;
import com.hedera.services.bdd.spec.transactions.network.HapiUncheckedSubmit;
import com.hedera.services.bdd.spec.transactions.system.HapiSysDelete;
import com.hedera.services.bdd.spec.transactions.system.HapiSysUndelete;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCall;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractCreate;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractDelete;
import com.hedera.services.bdd.spec.transactions.contract.HapiContractUpdate;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoCreate;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoDelete;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer;
import com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoUpdate;
import com.hedera.services.bdd.spec.transactions.file.HapiFileAppend;
import com.hedera.services.bdd.spec.transactions.file.HapiFileCreate;
import com.hedera.services.bdd.spec.transactions.file.HapiFileDelete;
import com.hedera.services.bdd.spec.transactions.file.HapiFileUpdate;

import java.util.function.Function;
import java.util.function.Supplier;

public class TxnVerbs {
	/* CRYPTO */
	public static HapiCryptoCreate cryptoCreate(String account) {
		return new HapiCryptoCreate(account);
	}
	public static HapiCryptoDelete cryptoDelete(String account) {
		return new HapiCryptoDelete(account);
	}
	@SafeVarargs
	public static HapiCryptoTransfer cryptoTransfer(Function<HapiApiSpec, TransferList>... providers) {
		return new HapiCryptoTransfer(providers);
	}
	public static HapiCryptoUpdate cryptoUpdate(String account) {
		return new HapiCryptoUpdate(account);
	}

	/* CONSENSUS */
	public static HapiTopicCreate createTopic(String topic) {
		return new HapiTopicCreate(topic);
	}
	public static HapiTopicDelete deleteTopic(String topic) {
		return new HapiTopicDelete(topic);
	}
	public static HapiTopicDelete deleteTopic(Function<HapiApiSpec, TopicID> topicFn) {
		return new HapiTopicDelete(topicFn);
	}
	public static HapiTopicUpdate updateTopic(String topic) {
		return new HapiTopicUpdate(topic);
	}
	public static HapiMessageSubmit submitMessageTo(String topic) {
		return new HapiMessageSubmit(topic);
	}
	public static HapiMessageSubmit submitMessageTo(Function<HapiApiSpec, TopicID> topicFn) {
		return new HapiMessageSubmit(topicFn);
	}

	/* FILE */
	public static HapiFileCreate fileCreate(String fileName) {
		return new HapiFileCreate(fileName);
	}
	public static HapiFileAppend fileAppend(String fileName) {
		return new HapiFileAppend(fileName);
	}
	public static HapiFileUpdate fileUpdate(String fileName) {
		return new HapiFileUpdate(fileName);
	}
	public static HapiFileDelete fileDelete(String fileName) {
		return new HapiFileDelete(fileName);
	}
	public static HapiFileDelete fileDelete(Supplier<String> fileNameSupplier) {
		return new HapiFileDelete(fileNameSupplier);
	}

	/* SYSTEM */
	public static HapiSysDelete systemFileDelete(String target) {
		return new HapiSysDelete().file(target);
	}
	public static HapiSysUndelete systemFileUndelete(String target) {
		return new HapiSysUndelete().file(target);
	}

	/* NETWORK */
	public static <T extends HapiTxnOp<T>> HapiUncheckedSubmit<T> uncheckedSubmit(HapiTxnOp<T> subOp) {
		return new HapiUncheckedSubmit<>(subOp);
	}

	/* SMART CONTRACT */
	public static HapiContractCall contractCallFrom(String details) {
		return HapiContractCall.fromDetails(details);
	}
	public static HapiContractCall contractCall(String contract) {
		return new HapiContractCall(contract);
	}
	public static HapiContractCall contractCall(String contract, String abi, Object... params) {
		return new HapiContractCall(abi, contract, params);
	}
	public static HapiContractCall contractCall(String contract, String abi, Function<HapiApiSpec, Object[]> fn) {
		return new HapiContractCall(abi, contract, fn);
	}
	public static HapiContractCreate contractCreate(String contract) {
		return new HapiContractCreate(contract);
	}
	public static HapiContractCreate contractCreate(String contract, String abi, Object... params) {
		return new HapiContractCreate(contract, abi, params);
	}
	public static HapiContractDelete contractDelete(String contract) {
		return new HapiContractDelete(contract);
	}
	public static HapiContractUpdate contractUpdate(String contract) {
		return new HapiContractUpdate(contract);
	}
}
