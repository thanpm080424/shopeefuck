const fs = require("node:fs");
const crypto = require("node:crypto");
const { initializeApp, applicationDefault } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getStorage } = require("firebase-admin/storage");

async function main() {
  const [apkPath, versionName, versionCodeText, minimumApiText, signerSha256] = process.argv.slice(2);
  if (!apkPath || !versionName || !versionCodeText || !minimumApiText || !/^[a-f0-9]{64}$/i.test(signerSha256 || "")) {
    throw new Error("Usage: node tools/publish_driver_release.cjs APK VERSION_NAME VERSION_CODE MIN_API SIGNER_CERT_SHA256");
  }
  const stat = fs.statSync(apkPath);
  if (!stat.isFile() || stat.size < 1024 * 1024) throw new Error("Driver APK is missing or unexpectedly small.");
  const hash = crypto.createHash("sha256").update(fs.readFileSync(apkPath)).digest("hex");
  const versionCode = Number(versionCodeText);
  const minimumApi = Number(minimumApiText);
  if (!Number.isInteger(versionCode) || !Number.isInteger(minimumApi)) throw new Error("Version fields must be integers.");

  const app = initializeApp({
    credential: applicationDefault(),
    projectId: "gymthan-b1495",
    storageBucket: "gymthan-b1495.firebasestorage.app",
  });
  const objectPath = `releases/driver-${versionCode}.apk`;
  const bucket = getStorage(app).bucket();
  await bucket.upload(apkPath, {
    destination: objectPath,
    resumable: true,
    validation: "crc32c",
    metadata: { contentType: "application/vnd.android.package-archive", cacheControl: "private, no-store" },
  });
  await getFirestore(app).collection("appConfig").doc("driverRelease").set({
    enabled: true,
    objectPath,
    sha256: hash,
    signerSha256: signerSha256.toLowerCase(),
    versionName,
    versionCode,
    minimumApi,
    sizeBytes: stat.size,
    publishedAt: FieldValue.serverTimestamp(),
  });
  console.log(`Published ${objectPath}; sha256=${hash}; size=${stat.size}`);
}

main().catch((error) => {
  console.error(`Driver release publish failed: ${error.message}`);
  process.exitCode = 1;
});
