"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.adminDeleteExpiredLicenses = exports.adminDeleteLicense = exports.adminUpdateLicense = exports.adminCreateLicense = exports.issueDriverApk = exports.verifyDriverLicense = void 0;
const app_1 = require("firebase-admin/app");
const firestore_1 = require("firebase-admin/firestore");
const storage_1 = require("firebase-admin/storage");
const https_1 = require("firebase-functions/v2/https");
(0, app_1.initializeApp)({ storageBucket: "gymthan-b1495.firebasestorage.app" });
const db = (0, firestore_1.getFirestore)();
const region = "asia-southeast1";
function requireUid(request) {
    if (!request.auth?.uid)
        throw new https_1.HttpsError("unauthenticated", "Đăng nhập trước khi tiếp tục.");
    return request.auth.uid;
}
function requireAdmin(request) {
    const uid = requireUid(request);
    if (request.auth?.token.admin !== true) {
        throw new https_1.HttpsError("permission-denied", "Tài khoản không có quyền quản trị.");
    }
    return uid;
}
function readDeviceId(value) {
    const id = typeof value === "string" ? value.trim().toLowerCase() : "";
    if (!/^[a-f0-9]{8,64}$/.test(id)) {
        throw new https_1.HttpsError("invalid-argument", "Mã thiết bị không hợp lệ.");
    }
    return id;
}
async function activeLicense(deviceId) {
    const snapshot = await (await existingLicenseRef(deviceId)).get();
    if (!snapshot.exists)
        throw new https_1.HttpsError("permission-denied", "Thiết bị chưa được cấp license.");
    const data = snapshot.data();
    const expiresAt = Number(data.expireUnixTime ?? 0);
    if (data.isActive !== true || !Number.isSafeInteger(expiresAt) || expiresAt <= Math.floor(Date.now() / 1000)) {
        throw new https_1.HttpsError("permission-denied", "License đã khóa hoặc hết hạn.");
    }
    return { data, expiresAt };
}
async function existingLicenseRef(deviceId) {
    const collection = db.collection("licenses");
    const lower = collection.doc(deviceId.toLowerCase());
    if ((await lower.get()).exists)
        return lower;
    const upper = collection.doc(deviceId.toUpperCase());
    if ((await upper.get()).exists)
        return upper;
    return lower;
}
exports.verifyDriverLicense = (0, https_1.onCall)({ region }, async (request) => {
    const deviceId = readDeviceId(request.data?.deviceId);
    const { data, expiresAt } = await activeLicense(deviceId);
    return {
        isActive: true,
        expireUnixTime: expiresAt,
        driverName: String(data.driverName ?? ""),
    };
});
exports.issueDriverApk = (0, https_1.onCall)({ region }, async (request) => {
    const deviceId = readDeviceId(request.data?.deviceId);
    await activeLicense(deviceId);
    const releaseSnapshot = await db.collection("appConfig").doc("driverRelease").get();
    const release = releaseSnapshot.data();
    if (!releaseSnapshot.exists || release?.enabled !== true) {
        throw new https_1.HttpsError("unavailable", "Bản tài xế chưa được phát hành trên máy chủ.");
    }
    const objectPath = String(release.objectPath ?? "");
    const sha256 = String(release.sha256 ?? "").toLowerCase();
    const signerSha256 = String(release.signerSha256 ?? "").toLowerCase();
    if (!objectPath.startsWith("releases/") || !/^[a-f0-9]{64}$/.test(sha256) || !/^[a-f0-9]{64}$/.test(signerSha256)) {
        throw new https_1.HttpsError("failed-precondition", "Thông tin bản phát hành trên máy chủ chưa hợp lệ.");
    }
    const bucket = (0, storage_1.getStorage)().bucket();
    const file = bucket.file(objectPath);
    const [exists] = await file.exists();
    if (!exists)
        throw new https_1.HttpsError("unavailable", "Không tìm thấy APK trên kho lưu trữ.");
    const expiresAt = Date.now() + 120_000;
    const [downloadUrl] = await file.getSignedUrl({
        version: "v4",
        action: "read",
        expires: expiresAt,
        responseDisposition: `attachment; filename="driver-${Number(release.versionCode)}.apk"`,
    });
    return {
        downloadUrl,
        sha256,
        signerSha256,
        versionName: String(release.versionName ?? ""),
        versionCode: Number(release.versionCode ?? 0),
        minimumApi: Number(release.minimumApi ?? 30),
        sizeBytes: Number(release.sizeBytes ?? 0),
        expiresAt,
    };
});
exports.adminCreateLicense = (0, https_1.onCall)({ region }, async (request) => {
    requireAdmin(request);
    const deviceId = readDeviceId(request.data?.deviceId);
    const driverName = String(request.data?.driverName ?? "").trim().slice(0, 100);
    const days = Number(request.data?.days);
    if (!Number.isInteger(days) || days < 1 || days > 3650) {
        throw new https_1.HttpsError("invalid-argument", "Số ngày cấp phải từ 1 đến 3650.");
    }
    const existingRef = await existingLicenseRef(deviceId);
    if ((await existingRef.get()).exists) {
        throw new https_1.HttpsError("already-exists", "Thiết bị đã có license.");
    }
    const expireUnixTime = Math.floor(Date.now() / 1000) + days * 86400;
    await db.collection("licenses").doc(deviceId).create({
        deviceId,
        driverName,
        isActive: true,
        expireUnixTime,
        createdBy: request.auth.uid,
        createdAt: firestore_1.FieldValue.serverTimestamp(),
        updatedAt: firestore_1.FieldValue.serverTimestamp(),
    });
    return { deviceId, expireUnixTime };
});
exports.adminUpdateLicense = (0, https_1.onCall)({ region }, async (request) => {
    requireAdmin(request);
    const deviceId = readDeviceId(request.data?.deviceId);
    const ref = await existingLicenseRef(deviceId);
    const snapshot = await ref.get();
    if (!snapshot.exists)
        throw new https_1.HttpsError("not-found", "Không tìm thấy license.");
    const addDays = Number(request.data?.addDays ?? 0);
    if (!Number.isInteger(addDays) || addDays < -3650 || addDays > 3650) {
        throw new https_1.HttpsError("invalid-argument", "Số ngày thay đổi không hợp lệ.");
    }
    const current = snapshot.data();
    let expiresAt = Number(current.expireUnixTime ?? 0);
    if (addDays !== 0) {
        expiresAt = Math.max(expiresAt, Math.floor(Date.now() / 1000)) + addDays * 86400;
    }
    const isActive = request.data?.isActive;
    if (typeof isActive !== "boolean")
        throw new https_1.HttpsError("invalid-argument", "Trạng thái license không hợp lệ.");
    const driverName = String(request.data?.driverName ?? "").trim().slice(0, 100);
    await ref.update({ isActive, expireUnixTime: expiresAt, driverName, updatedAt: firestore_1.FieldValue.serverTimestamp() });
    return { deviceId, isActive, expireUnixTime: expiresAt };
});
exports.adminDeleteLicense = (0, https_1.onCall)({ region }, async (request) => {
    requireAdmin(request);
    const deviceId = readDeviceId(request.data?.deviceId);
    await (await existingLicenseRef(deviceId)).delete();
    return { deleted: true };
});
exports.adminDeleteExpiredLicenses = (0, https_1.onCall)({ region }, async (request) => {
    requireAdmin(request);
    const now = Math.floor(Date.now() / 1000);
    let deletedCount = 0;
    while (true) {
        const snapshot = await db.collection("licenses").where("expireUnixTime", "<", now).limit(400).get();
        if (snapshot.empty)
            break;
        const batch = db.batch();
        for (const doc of snapshot.docs)
            batch.delete(doc.ref);
        await batch.commit();
        deletedCount += snapshot.size;
    }
    return { deletedCount };
});
