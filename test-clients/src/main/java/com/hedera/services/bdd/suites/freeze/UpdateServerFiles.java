package com.hedera.services.bdd.suites.freeze;

import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.freeze;
import static com.hedera.services.bdd.suites.utils.ZipUtil.createZip;
import static junit.framework.TestCase.fail;


public class UpdateServerFiles extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(UpdateServerFiles.class);
	private static String zipFile = "Archive.zip";
	private static final String DEFAULT_SCRIPT = "src/main/resource/testfiles/updateFeature/updateSettings/exec.sh";

	private static String uploadPath = "updateFiles/";

	private static int FREEZE_LAST_MINUTES = 2;
	private static String fileIDString;

	public static void main(String... args) {

		if (args.length > 0) {
			uploadPath = args[0];
		}

		if (args.length > 1) {
			fileIDString = args[1];
		}
		new UpdateServerFiles().runSuiteSync();
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return allOf(
				postiveTests()
		);
	}

	private List<HapiApiSpec> postiveTests() {
		return Arrays.asList(
				uploadGivenDirectory()
		);
	}

	// Zip all files under target directory and add an unzip and launch script to it
	// then send to server to update server
	private HapiApiSpec uploadGivenDirectory() {

		log.info("Creating zip file from " + uploadPath);
		final String temp_dir = "temp/";
		final String sdk_dir = temp_dir + "sdk/";
		byte[] data = null;
		try {
			//create a temp sdk directory
			File directory = new File(temp_dir);
			if (directory.exists()) {
				// delete everything in it recursively

				FileUtils.cleanDirectory(directory);

			} else {
				directory.mkdir();
			}

			(new File(sdk_dir)).mkdir();
			//copy files to sdk directory
			FileUtils.copyDirectory(new File(uploadPath), new File(sdk_dir));
			createZip(temp_dir, zipFile, DEFAULT_SCRIPT);
			String uploadFile = zipFile;

			log.info("Uploading file " + uploadFile);
			data = Files.readAllBytes(Paths.get(uploadFile));
		} catch (IOException e) {
			log.error("Directory creation failed", e);
			fail("Directory creation failed");
		}
		return defaultHapiSpec("uploadFileAndUpdate")
				.given(
						fileUpdate(APP_PROPERTIES)
								.overridingProps(Map.of("maxFileSize", "2048000")),
						fileCreate("newFile.zip").contents("to-be-overwritten"),
						UtilVerbs.updateLargeFile(GENESIS, "newFile.zip", ByteString.copyFrom(data))
				).when(
						freeze().setFileName("newFile.zip")
								.startingIn(60).seconds().andLasting(FREEZE_LAST_MINUTES).minutes()
				).then(
				);
	}
}