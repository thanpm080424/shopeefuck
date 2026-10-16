const { applicationDefault, initializeApp } = require("firebase-admin/app");
const { getAuth } = require("firebase-admin/auth");

const email = String(process.argv[2] || "").trim().toLowerCase();
if (!email.includes("@")) {
  console.error("Usage: node functions/scripts/set-admin-claim.cjs admin@example.com");
  process.exit(2);
}

initializeApp({ credential: applicationDefault(), projectId: "gymthan-b1495" });
getAuth().getUserByEmail(email)
  .then(async (user) => {
    await getAuth().setCustomUserClaims(user.uid, { ...(user.customClaims || {}), admin: true });
    console.log(`Admin claim assigned to ${email}. Sign out and sign in again in HK Admin Pro.`);
  })
  .catch((error) => {
    console.error(`Could not set admin claim: ${error.code || error.message}`);
    process.exitCode = 1;
  });
