# 이북 AI 사전

선택 → 검색 → AI에서 이북리더기는 **앱에 포함된 Claude 사전 화면**을, 일반 폰은 기존 ChatGPT를 사용한다. 둘 다 선택한 글에 ` 뜻`을 붙인다. 기기 판별이 아직 없으면 AI 탭에서 비동기로 확인한다.

ChatGPT 로그인이나 사이트 서버가 필요 없다. 기존 `chatgpt.site`와 `extras.einkAiSite` 설정은 사용하지 않는다.

## 사용

1. 이북에서 본문을 선택하고 검색 → AI 탭을 연다.
2. 처음에 표시되는 Claude API 연결 창에 Anthropic API 키를 한 번 입력한다. `이 기기에 키 저장`을 켜면 이 앱의 WebView localStorage에 보관한다. 끄면 현재 창에서만 사용한다.
3. 기본값은 Haiku 5.5 / 중간. 입력창의 모델 버튼에서 Sonnet 5.5·Opus 5.5, 낮음·중간·높음·엑스트라를 선택한다.
4. 메뉴 → Claude API 연결 → 연결 해제로 저장된 키를 삭제할 수 있다. 앱 데이터 삭제·앱 제거 시에도 키가 사라진다.

키를 저장하는 localStorage는 암호화된 금고가 아니다. 개인 기기에서만 사용하고, 다른 사이트에 키를 넣지 않는다. 외부 브라우저와 앱의 키 저장 공간은 별개다.

## 성능과 보안

- `app/src/main/assets/ai-dictionary.html` 한 파일에 HTML·CSS·JavaScript를 모두 넣었다. 화면을 여는 HTTP 요청은 없다. 인터넷은 Claude 답변을 요청할 때만 필요하다.
- 외부 폰트·SDK·프레임워크·음성·애니메이션·로그인 코드가 없다. 스트리밍 첫 글은 즉시, 이후 화면 갱신은 500ms 단위로 묶고 최종 결과를 즉시 표시한다.
- WebView는 앱의 HTML을 고정 HTTPS origin에서 제공한다. 이 주소는 앱 내 전용이며 일반 브라우저에 넣는 사이트 주소가 아니다.
- API는 `https://api.anthropic.com/v1/messages`에 브라우저 직접 호출 헤더를 붙여 전송한다. 중간 프록시 서버가 없다. 키는 요청 헤더에만 있고 질문·URL·소스에는 넣지 않는다.
- CSP는 외부 스크립트·프레임·폼 제출을 차단하며, 네트워크 요청은 Claude API로 제한한다. WebView 자체도 다른 URL의 요청과 페이지 이동을 막는다. JavaScript 네이티브 브리지는 사용하지 않는다.
- WebView 데이터는 Android 클라우드 백업과 기기 이전에서 제외한다. 앱 자체의 설정 백업에도 키를 넣지 않는다.
- 이북에서 다른 사전을 보는 동안 AI를 미리 보내지 않는다. 대화는 짧게 제한하며 창이 닫히면 종료한다. 별도 대화 저장 서버를 두지 않는다.
- 모델 ID는 `claude-haiku-5-5`, `claude-sonnet-5-5`, `claude-opus-5-5`, 엑스트라는 `xhigh`다. 직접 호출이 API 계정 정책이나 오래된 WebView에서 막히면 연결 오류가 표시된다.

같은 HTML은 별도 HTTPS 정적 호스팅에도 사용할 수 있다. GitHub Pages를 쓰더라도 계정 로그인이나 API 키를 저장하는 서버는 필요 없다. 앱 안에서는 호스팅 없이 즉시 열리는 쪽이 기본이다.

## 검증

`node --test tools/ai-dictionary/test.mjs`는 API 요청·스트림·취소·키 저장·초기 화면·CSP와 크기 제한을 확인한다. Android URL 제한과 이북/폰 분기는 Gradle 단위 테스트로 확인한다. 실제 키를 사용한 응답과 e-ink 화면 반응은 실기기에서 확인한다.

HTML의 스크립트를 바꾸면 CSP의 `sha256-` 값도 해당 `<script>` 본문 해시로 갱신해야 한다. 테스트가 해시 불일치를 검출한다.
