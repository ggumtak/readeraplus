# ReadEraPlus 동기화 서버

앱 운영자가 한 번 설정하면 사용자는 두 기기에서 같은 이메일로 로그인한다.

1. ReadEraPlus 전용 Supabase 프로젝트를 만들거나 선택한다. SQL Editor에서 migrations/202611280001_reader_shelf.sql 전체를 한 번 실행한다. 비공개 버킷과 계정별 접근 정책, 책 목록과 독서 위치 테이블을 만든다.
2. Authentication → Users에서 개인용 이메일 계정을 만들어 주거나, 앱에서 회원가입을 제공하려면 이메일 가입과 확인 메일 발송을 구성한다. 기본 메일 발송기는 팀 외부 주소로 보내는 데 제한이 있다. 운영 시에는 SMTP와 확인 후 이동할 Site URL을 설정한다.
3. 앱 저장소의 app/src/main/res/values/sync_project.xml에 reader_supabase_url과 reader_supabase_key 값을 입력한다. 각각 프로젝트 HTTPS URL과 sb_publishable_ 로 시작하는 공개 키다. 관리자 키를 앱에 넣으면 안 된다.
4. 설정을 배포 브랜치에 반영하면 Build APK 워크플로가 새 버전을 배포한다. 두 기기에 새 빌드를 설치하고 설정 → 동기화 서재에서 로그인한다. 양쪽에서 책 파일 동기화를 켠 후 한 기기에서 책을 열고, 다른 기기에서 동기화한 책 목록을 불러온다.

책 파일 크기와 저장 용량에는 해당 프로젝트의 Storage 한도가 적용된다. Google Drive 등에 있던 원본은 기기에서 다시 열어 새 서재에 올릴 수 있다. 서버 사이에 기존 자료를 자동 복사하지 않는다.
