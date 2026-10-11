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
 * After that every build puts ReaderaPlus-<build>.apk into the Drive folder FOLDER and removes older ones.
 *
 * In-app update (2026-10-11): the app asks this web app `?what=latest` for the newest build and downloads it through
 * a "anyone with the link" share of that one file. After replacing the code, keep the same URL:
 * 배포 → 배포 관리 → (연필) 수정 → 버전: 새 버전 → 배포. No new secret is needed.
 */
const KEY = 'CHANGE-ME-to-a-long-password';
const FOLDER = 'ReaderaPlus';

function doPost(e) {
  try {
    const req = JSON.parse(e.postData.contents);
    if (!KEY || KEY.indexOf('CHANGE-ME') === 0 || req.key !== KEY) return text('denied');
    const name = String(req.name || '');
    if (!/^ReaderaPlus-\d+\.apk$/.test(name)) return text('bad name');
    const folders = DriveApp.getFoldersByName(FOLDER);
    const folder = folders.hasNext() ? folders.next() : DriveApp.createFolder(FOLDER);
    const blob = Utilities.newBlob(Utilities.base64Decode(req.data), 'application/vnd.android.package-archive', name);
    const file = folder.createFile(blob);
    // The app downloads the newest build by its link (it is the only file shared, and only while it is the newest).
    try {
      file.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
    } catch (err) {
      // doGet shares it again when the app asks; the upload itself still counts.
    }
    // Older builds go to the trash (restorable for 30 days); the new one stays.
    const files = folder.getFiles();
    while (files.hasNext()) {
      const f = files.next();
      if (f.getId() !== file.getId() && /^ReaderaPlus-\d+\.apk$/.test(f.getName())) f.setTrashed(true);
    }
    return text('ok ' + name);
  } catch (err) {
    return text('error ' + err);
  }
}

/** `?what=latest` → {"build":185,"id":"<Drive file id>"} for the newest ReaderaPlus-<build>.apk, or {"build":0}. */
function doGet(e) {
  try {
    const p = (e && e.parameter) || {};
    if (p.what !== 'latest') return text('ReaderaPlus');
    let best = null;
    let bestBuild = 0;
    const folders = DriveApp.getFoldersByName(FOLDER);
    if (folders.hasNext()) {
      const files = folders.next().getFiles();
      while (files.hasNext()) {
        const f = files.next();
        if (f.isTrashed()) continue;
        const m = /^ReaderaPlus-(\d+)\.apk$/.exec(f.getName());
        if (m && Number(m[1]) > bestBuild) {
          bestBuild = Number(m[1]);
          best = f;
        }
      }
    }
    if (!best) return json({ build: 0 });
    if (best.getSharingAccess() !== DriveApp.Access.ANYONE_WITH_LINK) {
      best.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
    }
    return json({ build: bestBuild, id: best.getId() });
  } catch (err) {
    return json({ build: 0, error: String(err) });
  }
}

function json(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

function text(s) {
  return ContentService.createTextOutput(s);
}
