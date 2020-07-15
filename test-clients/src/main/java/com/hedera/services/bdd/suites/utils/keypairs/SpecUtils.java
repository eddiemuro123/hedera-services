package com.hedera.services.bdd.suites.utils.keypairs;

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

import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hedera.services.legacy.core.AccountKeyListObj;
import com.hedera.services.legacy.core.KeyPairObj;
import com.hedera.services.legacy.proto.utils.CommonUtils;
import org.apache.commons.codec.binary.Hex;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyStoreException;
import java.util.List;
import java.util.Map;

import static com.hedera.services.bdd.spec.HapiPropertySource.asAccount;
import static java.nio.file.Files.newBufferedWriter;

public class SpecUtils {
	public static String asSerializedOcKeystore(
			File aes256EncryptedPkcs8Pem,
			String passphrase,
			AccountID id
	) throws KeyStoreException, IOException {
		var keyStore = Ed25519KeyStore.read(passphrase.toCharArray(), aes256EncryptedPkcs8Pem);
		return asSerializedOcKeystore(keyStore.get(0), id);
	}

	public static KeyPairObj asOcKeystore(File aes256EncryptedPkcs8Pem, String passphrase) throws KeyStoreException {
		var keyStore = Ed25519KeyStore.read(passphrase.toCharArray(), aes256EncryptedPkcs8Pem);
		var keyPair = keyStore.get(0);
		return new KeyPairObj(
				Hex.encodeHexString(keyPair.getPublic().getEncoded()),
				Hex.encodeHexString(keyPair.getPrivate().getEncoded()));
	}

	public static String asSerializedOcKeystore(KeyPair keyPair, AccountID id) throws IOException {
		var hexPublicKey = Hex.encodeHexString(keyPair.getPublic().getEncoded());
		var hexPrivateKey = Hex.encodeHexString(keyPair.getPrivate().getEncoded());
		var keyPairObj = new KeyPairObj(hexPublicKey, hexPrivateKey);
		var keys = new AccountKeyListObj(id, List.of(keyPairObj));

		var baos = new ByteArrayOutputStream();
		var oos = new ObjectOutputStream(baos);
		oos.writeObject(Map.of("START_ACCOUNT", List.of(keys)));
		oos.close();

		return CommonUtils.base64encode(baos.toByteArray());
	}

	public static String asSerializedLegacyOcKeystore(
			File aes256EncryptedPkcs8Pem,
			String passphrase,
			AccountID id
	) throws KeyStoreException, IOException {
		var keyStore = Ed25519KeyStore.read(passphrase.toCharArray(), aes256EncryptedPkcs8Pem);
		return asSerializedLegacyOcKeystore(keyStore.get(0), id);
	}

	public static String asSerializedLegacyOcKeystore(KeyPair keyPair, AccountID id) throws IOException {
		var hexPublicKey = Hex.encodeHexString(keyPair.getPublic().getEncoded());
		var hexPrivateKey = Hex.encodeHexString(keyPair.getPrivate().getEncoded());
		var keyPairObj = new com.hedera.services.legacy.core.KeyPairObj(hexPublicKey, hexPrivateKey);
		var keys = new com.hedera.services.legacy.core.AccountKeyListObj(id, List.of(keyPairObj));

		var baos = new ByteArrayOutputStream();
		var oos = new ObjectOutputStream(baos);
		oos.writeObject(Map.of("START_ACCOUNT", List.of(keys)));
		oos.close();

		return CommonUtils.base64encode(baos.toByteArray());
	}

	public static void main(String... args) throws Exception {
		var genesisLoc = new File("src/main/resource/mainnet-account950.pem");
		var txt = asSerializedLegacyOcKeystore(genesisLoc, "swirlds", asAccount("0.0.950"));
		var out = newBufferedWriter(Paths.get("MainStartupAccount.txt"));
		out.write(txt);
		out.close();
	}
}
