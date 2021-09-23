package com.hedera.services.utils;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
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

import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.extensions.LoggingTarget;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.zip.ZipInputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@Disabled
@ExtendWith({ LogCaptureExtension.class, MockitoExtension.class })
class UnzipUtilityTest {
	@Mock
	private ZipInputStream zipIn;

	@LoggingTarget
	private LogCaptor logCaptor;
	@LoggingSubject
	private UnzipUtility subject;

	@Test
	void unzipAbortWithRiskyFile() throws Exception {
		final var zipFile = "src/test/resources/testfiles/updateFeature/bad.zip";
		final var data = Files.readAllBytes(Paths.get(zipFile));
		final var dstDir = "./temp";

		assertThrows(IllegalArgumentException.class, () -> {
			UnzipUtility.unzip(data, dstDir);
		});
	}

	@Test
	void unzipSuccessfully() throws Exception {
		final var zipFile = "src/test/resources/testfiles/updateFeature/update.zip";
		final var data = Files.readAllBytes(Paths.get(zipFile));
		final var dstDir = "./temp";

		assertDoesNotThrow(() -> UnzipUtility.unzip(data, dstDir));

		final var file3 = new File("./temp/sdk/new3.txt");
		assertTrue(file3.exists());
		file3.delete();
	}

	@Test
	void logsAtErrorWhenUnableToExtractFile() throws IOException {
		final var tmpFile = "shortLived.txt";
		given(zipIn.read(any())).willThrow(IOException.class);

		assertDoesNotThrow(() -> UnzipUtility.extractSingleFile(zipIn, tmpFile));
		assertThat(logCaptor.errorLogs(), contains("Unable to write to file shortLived.txt java.io.IOException: null"));

		new File(tmpFile).delete();
	}
}
