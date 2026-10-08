# 빌드한 APK를 구글 드라이브에 자동으로 올리기

CI(`.github/workflows/build.yml`)는 빌드에 성공할 때마다 `ReaderaPlus-<빌드번호>.apk`를 GitHub Release `latest`에 올린다.
아래 비밀값을 한 번 등록하면 같은 APK가 구글 드라이브에도 올라간다(최근 5개만 남기고 오래된 것은 지움).
비밀값이 없으면 이 단계는 아무것도 하지 않고, 드라이브 쪽 오류는 빌드를 실패시키지 않는다.

## 1. rclone 설정 파일 만들기 (PC에서 한 번)

1. https://rclone.org/downloads/ 에서 rclone을 받는다.
2. 명령 창에서 `rclone config` 실행 → `n`(새 remote)
   - name: `gdrive`
   - Storage: `drive` (Google Drive)
   - client_id / client_secret: 빈칸으로 Enter
   - scope: `drive.file` (rclone이 만든 파일만 다룸. 드라이브의 다른 파일은 건드리지 않는다)
   - 나머지는 Enter, "Use web browser to automatically authenticate?" → `y` → 브라우저에서 구글 계정 허용
   - Shared Drive → `n`, 마지막에 `y`(저장), `q`(종료)
3. 설정 파일을 base64 한 줄로 바꾼다.
   - Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:APPDATA\rclone\rclone.conf")) | Set-Clipboard`
   - macOS: `base64 -i ~/.config/rclone/rclone.conf | pbcopy`
   - Linux: `base64 -w0 ~/.config/rclone/rclone.conf`

## 2. GitHub에 등록

저장소 → Settings → Secrets and variables → Actions
- **Secrets** → New repository secret: 이름 `RCLONE_CONFIG_B64`, 값 = 위에서 복사한 한 줄
- (선택) **Variables** → `DRIVE_DEST`: 올릴 위치. 기본 `gdrive:ReaderaPlus` (내 드라이브의 ReaderaPlus 폴더, 없으면 만듦)
- (선택) **Variables** → `DRIVE_KEEP`: 남길 APK 개수. 기본 `5`

다음 빌드부터 드라이브에 올라간다. 확인: Actions의 빌드 → "Upload APK to Google Drive" 단계 로그.

## 주의
- 이 비밀값은 그 구글 계정 드라이브에 rclone이 만든 파일을 읽고 쓸 수 있는 권한이다. 저장소를 공개로 바꾸지 말 것
  (포크의 풀 리퀘스트에는 비밀값이 전달되지 않는다).
- 권한을 끊으려면 구글 계정 → 보안 → 타사 앱 액세스에서 rclone을 삭제하고, GitHub 비밀값도 지운다.
