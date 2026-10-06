/**
 * ReaderaPlus: receives each new APK from GitHub Actions and keeps it in your Google Drive.
 * Runs in YOUR Google account (Apps Script), so the APK goes to your own Drive; nothing else is touched
 * except ReaderaPlus-*.apk files inside the folder below.
 *
 * Setup from a phone (Chrome, menu → "데스크톱 사이트" on):
 *  1. script.google.com → 새 프로젝트. Replace everything in Code.gs with this file.
 *  2. Change KEY below to your own long password (letters and numbers). Save (disk icon).
 *  3. 배포 → 새 배포 → 유형 "웹 앱" → 실행: 나 / 액세스 권한: 모든 사용자 → 배포.
 *     Google asks for permission: 액세스 승인 → your account → 고급 → "(안전하지 않음)으로 이동" → 허용.
 *  4. Copy the 웹 앱 URL (ends with /exec).
 *  5. github.com → the repository → Settings → Secrets and variables → Actions → New repository secret:
 *     GDRIVE_UPLOAD_URL = the URL from step 4, GDRIVE_UPLOAD_KEY = the KEY from step 2.
 * After that every build puts ReaderaPlus-<version>.apk (e.g. ReaderaPlus-0.2.0.apk; older builds ReaderaPlus-<build>.apk) into the Drive folder FOLDER and removes older ones.
 */
const KEY = 'CHANGE-ME-to-a-long-password';
const FOLDER = 'ReaderaPlus';

function doPost(e) {
  try {
    const req = JSON.parse(e.postData.contents);
    if (!KEY || KEY.indexOf('CHANGE-ME') === 0 || req.key !== KEY) return text('denied');
    const name = String(req.name || '');
    if (!/^ReaderaPlus-[0-9][0-9.]*\.apk$/.test(name)) return text('bad name');
    const folders = DriveApp.getFoldersByName(FOLDER);
    const folder = folders.hasNext() ? folders.next() : DriveApp.createFolder(FOLDER);
    const blob = Utilities.newBlob(Utilities.base64Decode(req.data), 'application/vnd.android.package-archive', name);
    const file = folder.createFile(blob);
    // Older builds go to the trash (restorable for 30 days); the new one stays.
    const files = folder.getFiles();
    while (files.hasNext()) {
      const f = files.next();
      if (f.getId() !== file.getId() && /^ReaderaPlus-[0-9][0-9.]*\.apk$/.test(f.getName())) f.setTrashed(true);
    }
    return text('ok ' + name);
  } catch (err) {
    return text('error ' + err);
  }
}

function text(s) {
  return ContentService.createTextOutput(s);
}
