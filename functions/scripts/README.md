# HKPick Firebase setup

1. In Firebase Console for `gymthan-b1495`, enable **Authentication → Email/Password** and create the first administrator account. Email/password is used only to secure HK Admin Pro; drivers do not sign in.
2. Sign in to Firebase CLI (`firebase login`) and deploy with `firebase deploy --only functions,firestore:rules,storage`.
   The project must use the Blaze plan for Cloud Functions/Storage signed URLs. Grant the deployed Functions runtime service account permission to sign blobs (`roles/iam.serviceAccountTokenCreator` on that service account) and read the release object (`roles/storage.objectViewer` on the bucket).
3. In Cloud Shell or a trusted machine with Google Application Default Credentials for this project, run `node functions/scripts/set-admin-claim.cjs ADMIN_EMAIL`. The email must be the administrator account created in step 1. Never put service-account credentials in either APK.
4. Reauthenticate in HK Admin Pro. Use **Cấp Key Mới** with the driver's Android ID, name, and license term. The driver app and phone installer verify the license against that device ID; no driver email/password is required. The driver can read the ID on the activation screen, or the phone installer reads it over ADB.
5. Publish a signed driver release with `powershell -ExecutionPolicy Bypass -File tools/publish_driver_release.ps1 -ApkPath "C:\path\hkpick_driver_release.apk"`.
6. Build the phone installer with `powershell -ExecutionPolicy Bypass -File tools/build_phone_installer.ps1` and distribute only `HKPick_Phone_Installer.apk`.

The installer downloads only after the server confirms that the target Android ID has an active, unexpired license, then validates the APK SHA-256 and signing certificate. Signed download URLs last two minutes. A recipient who has already been authorized to download can still retain/copy the APK; client-side packaging cannot make an APK impossible to copy.
