# 자비스 포크 변경 기록 (안드로이드 앱)

## 기준

- 원본: `tiann/hapi` 태그 **`v0.30.7`** (커밋 `0239edf`). 서버에 깔릴 npm 허브와 같은 버전입니다.
- 프로토콜 버전(앱·허브가 맞아야 붙는 번호): `1`. 이번 작업은 이 번호를 건드리지 않습니다.
- 고친 곳은 `android/app/` 의 화면(UI) 층과 이 `docs/jarvis/` 문서뿐입니다. `hub/`·`cli/`·`shared/`·`web/`·`ios/`, 그리고 `android/core/`(프로토콜·데이터 층)는 그대로입니다.
- 원본을 따라갈 때: 허브와 앱을 **같은 태그**로 함께 올리고, 아래 「고친 원본 파일」만 다시 맞춰 보면 됩니다.

## 고친 원본 파일 (파일마다 한 줄 이유)

모두 `android/app/src/main/` 아래입니다. 호출부만 바꾸고, 새 기능은 새 파일에 두었습니다.

| 파일 | 바뀐 줄 | 이유 |
|---|---|---|
| `kotlin/.../feature/chat/ChatScreen.kt` | +4 | 입력창 위에 진행 표시줄(`ActivityStatusBar`)을 끼움 |
| `kotlin/.../feature/chat/blocks/ToolCallBlockView.kt` | +5 −15 | 대화 속 권한 요청 카드를 새 패널(`JarvisPendingRequest`)로 바꿈 |
| `kotlin/.../feature/chat/ChatHost.kt` | +1 −2 | 도구 상세 화면의 권한 요청도 같은 새 패널로 바꿈 |
| `kotlin/.../feature/chat/blocks/PermissionActionViews.kt` | 3줄 `private`→`internal` | 새 패널이 원본과 **같은 버튼 규칙**(Codex 여부, 편집 도구 목록)을 쓰도록 공개만 함 |
| `kotlin/.../feature/chat/composer/QueuedMessagesBar.kt` | 1줄 | 하드코딩된 영어 `"Retry"` 를 기존 문자열 리소스 `chat_retry` 로 바꿈 |
| `kotlin/.../ui/theme/Theme.kt` | +1 | 앱 전체 글꼴 설정에 한국어 줄바꿈 규칙(`ReadableTypography`)을 넣음 |
| `kotlin/.../ui/markdown/Markdown.kt` | 값 2개 + 주석 1줄 | 긴 답이 숨 쉬게: 줄 간격 24→26sp, 문단 간격 10→14dp |
| `kotlin/.../feature/settings/LanguagePrefs.kt` | +2 −1 | 앱 언어 목록에 한국어(`KOREAN`) 추가 |
| `kotlin/.../feature/settings/SettingsScreen.kt` | +1 | 언어 선택지 이름 「한국어」 |
| `res/xml/locales_config.xml` | +1 | 안드로이드 「앱별 언어」 설정에 `ko` 등록 |

## 새로 만든 파일

| 파일 | 하는 일 |
|---|---|
| `android/app/src/main/res/values-ko/strings.xml` | 원본 문자열 518개 전부의 한국어 번역 |
| `android/app/src/main/res/values/strings_jarvis.xml` | 새 화면용 문자열(영어 기본값) 21개 |
| `android/app/src/main/res/values-ko/strings_jarvis.xml` | 위의 한국어 |
| `android/app/src/main/res/values-zh-rCN/strings_jarvis.xml` | 위의 중국어(번역 빠짐 경고를 막으려고) |
| `android/app/src/main/kotlin/.../feature/chat/jarvis/JarvisChatModel.kt` | 화면 없는 순수 계산: 진행 단계 판정, 권한 요청 종류·원문, 「한 번 눌러 답하기」 판정 |
| `android/app/src/main/kotlin/.../feature/chat/jarvis/ActivityStatusBar.kt` | 진행 표시줄 화면 |
| `android/app/src/main/kotlin/.../feature/chat/jarvis/JarvisPermissionPanel.kt` | 질문 큰 버튼, 권한 요청 요약·원문 접기·두 번 눌러 승인 |
| `android/app/src/main/kotlin/.../ui/theme/ReadableTypography.kt` | 한국어 어절 단위 줄바꿈 글꼴 설정 |
| `android/app/src/test/kotlin/.../feature/chat/jarvis/JarvisChatModelTest.kt` | 위 순수 계산의 JVM 시험 18개 |
| `docs/jarvis/X5-코드확인.md` | 0단계 코드 확인 답 |
| `docs/jarvis/CHANGES.md` | 이 문서 |

(`...` 은 `app/hapi/companion` 입니다.)

## 단계별로 무엇을 했나

1. **한국어**
   - 원본 문자열 전부를 `values-ko` 로 번역했습니다. 문장은 「~합니다」, 버튼은 명사형(「승인」, 「거절」, 「다시 시도」)입니다.
   - 설정 → 언어에서 「한국어」를 고를 수 있고, 폰이 한국어면 저절로 한국어로 뜹니다.
   - 줄바꿈: 앱 전체 글꼴에 `LineBreak.WordBreak.Phrase` 를 걸었습니다. 안드로이드 13 이상(갤럭시 A36 포함)에서 `LineBreakConfig` 의 phrase 스타일로 바뀌어 어절 중간에서 덜 끊깁니다. 12 이하는 그냥 무시합니다. **실기기 확인은 아직 못 했습니다.**
2. **진행이 보이게**
   - 입력창 바로 위에 한 줄: 「생각 중 / 도구 사용 중 / 승인 기다리는 중 / 답 쓰는 중 / 끝」 · 「N단계」 · 걸린 시간.
   - 도구를 쓰는 중이면 둘째 줄에 「무슨 도구로 무엇을」(예: `💻 bun test`, `📖 파일 읽기 · Reducer.kt`)이 뜹니다. 누르면 그 도구의 원문 화면이 열립니다.
   - 판정은 허브가 주는 공통 블록(사용자 말·답 글·생각·도구 호출)과 세션의 `thinking` 표시만 봅니다. Claude·Codex 이름으로 갈라지는 곳이 없습니다.
   - 도구 접기: 원본에 이미 있습니다. 이어지는 도구 호출은 「도구 N개」 한 줄로 묶이고, 도구 카드는 한 줄 요약이며, 누르면 원문 화면이 열립니다. 그래서 새로 만들지 않았습니다.
3. **긴 글 읽기**
   - 원본이 이미 표 가로 스크롤, 코드 블록, 제목·목록, 시스템 글자 크기(sp 단위)를 지원합니다.
   - 줄 간격과 문단 간격만 조금 넓혔습니다(위 표).
4. **버튼과 확인**
   - 질문 도구: 질문이 하나이고 하나만 고르는 형태면, 선택지가 **높이 56dp 이상 큰 버튼**으로 뜨고 한 번 누르면 답이 갑니다. 「직접 입력하기」를 누르면 원래 입력 양식이 열립니다. 여러 질문·여러 개 고르기는 원래 양식 그대로입니다. 보내는 답의 모양은 원래 양식과 똑같습니다(시험으로 확인).
   - 권한 요청: 「명령 실행 요청 / 파일 변경 요청 / 웹 접근 요청 / 도구 사용 요청」 머리줄 → 한 줄 요약 → 「원문 보기」(누르면 명령·경로 원문이 펼쳐짐) → 버튼 순서입니다.
   - 실수 방지: 「거절」은 왼쪽, 「승인」은 오른쪽에 떨어뜨려 놓았습니다. 「승인」은 **한 번 누르면 「한 번 더 눌러 승인」으로 바뀌고 두 번째에 승인**됩니다(4초 지나면 되돌아감). 카드가 뜬 직후 0.7초는 눌리지 않습니다. 「이 세션 동안 허용」 같은 넓은 허락은 「다른 선택」 메뉴 안에 있습니다.
   - 위험한지 아닌지는 앱이 판단하지 않습니다. 모든 요청을 같은 모양으로 보여 줍니다.

## 새 라이브러리

없습니다.

## 시험·빌드 결과

- 환경: 이 클라우드 세션에 안드로이드 SDK 가 없어서, 작업 중에 SDK(platform 36, build-tools 36/35)를 받아 깔았습니다.
- Maven Central 이 429(요청 너무 많음)를 돌려줘서, 이 세션에서만 Google 의 Maven Central 미러를 먼저 쓰도록 `~/.gradle/init.d` 에 스크립트를 두었습니다. 저장소에는 넣지 않았습니다.
- `./gradlew :app:assembleDebug` — **성공**.
- `./gradlew :core:protocol:test :core:data:testDebugUnitTest :app:testDebugUnitTest` — **성공**. 실패 0 (protocol 268개, data 241개, app 252개. app 중 새 시험 18개).
- `./gradlew :app:lintDebug` — PR 설명에 결과를 적습니다.
- **못 돌린 것**: 기기·에뮬레이터가 없어 Compose 계측 시험(`connectedDebugAndroidTest`)과 실제 화면 확인은 못 했습니다.

## 남은 영어 (일부러 그대로 둠)

- 대화창의 회색 사건 줄(예: 모드 전환, 오류) 문구: `android/core/protocol` 의 `getEventPresentation` 이 만듭니다. 웹과 맞춘 프로토콜 층(시험 기준 데이터와 대조됨)이라 손대지 않았습니다.
- 알림 제목·본문(「Ready for input」 등): 허브가 만듭니다 → 아래 「엔진 변경 필요」.
- 일부 도구 이름 그대로 표시(예: `MCP: …`, 도구 원래 이름).

## 엔진 변경 필요 (허브·CLI 를 고쳐야 되는 것 — 이번엔 안 함)

1. **모델 없이 고정 문구 넣기·푸시만 보내기**: REST 에 그런 길이 없습니다. 넣은 말은 늘 사용자 말로 에이전트에게 갑니다(X5 문서 2번).
2. **꺼진 세션에 먼저 말 걸기**: 허브가 409 로 거절합니다. 크론이 `resume` 을 먼저 부르거나, 허브가 자동으로 깨워 주는 기능이 필요합니다(X5 문서 1번).
3. **바깥에서 넣은 말 표식**: `meta`(예: `sentFrom`)를 보내는 쪽이 정할 수 없습니다. 지금은 `localId` 접두어(허브 DB 에만 남음)나 본문 표식만 됩니다(X5 문서 3번).
4. **「되돌릴 수 없는 행동」 표시**: 서버가 판정한 결과를 앱에 보여 주려면, 권한 요청에 그 판정을 싣는 칸이 허브·프로토콜에 있어야 합니다. 지금은 없어서 앱은 모든 요청을 같게 보여 줍니다.
5. **알림 문구 한국어**: 네이티브 알림 문구는 허브가 영어로 만듭니다(`hub/src/notifications/nativeNotificationComposer.ts`).
6. **Firebase 없는 빌드의 알림**: 앱이 닫혀 있을 때 알림을 받을 길이 없습니다. Firebase 프로젝트를 만들거나 PWA 웹 푸시를 써야 합니다(X5 문서 6번).
