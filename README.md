# TranslateApp

사이트 링크를 입력하면 WebView로 실제 사이트를 그대로 띄우고, 화면 안의 텍스트만
온디바이스로 인라인 번역해주는 개인용 Android 앱. 사이트 안의 링크/버튼/입력폼은
그대로 동작하므로, 원본 사이트를 쓰듯 클릭해서 페이지를 넘기면 새 페이지도 자동으로
다시 번역된다.

## 아키텍처

- `webview` — `PageTranslator`: WebView가 페이지 로드를 마칠 때마다(`onPageFinished`)
  `assets/inline_translate.js`를 주입해 블록 단위 텍스트를 수집하고, 번역 결과를 같은
  자리에 다시 심어 넣는다(JS `JavascriptInterface` 브리지로 Kotlin ↔ JS 통신).
- `translation` — 번역 엔진 인터페이스. 두 구현체가 있다:
  - `MLKitTranslator` — Google ML Kit 온디바이스 번역 (완전 오프라인, 무료, 항상 동작)
  - `GoogleTranslateEngine` — Google Cloud Translation API (서버급 신경망 모델이라
    훨씬 자연스럽지만 API 키와 인터넷 필요)
  - `FallbackTranslator` — 위 둘을 감싸서, API 키가 설정되어 있으면 Cloud Translation을
    먼저 쓰고 키가 없거나 호출이 실패하면 자동으로 ML Kit으로 전환한다. MainActivity는
    이 클래스만 `TranslationEngine`으로 사용하므로 어느 엔진이 실제로 쓰였는지 신경 쓸 필요 없다.
- `cache` — Room DB 기반 번역 결과 캐싱. **블록(문단) 단위**로 캐싱하므로 같은 문구가
  여러 페이지에 반복돼도(메뉴, 공통 문구 등) 재번역하지 않는다.
- `ui` — MainActivity (URL 입력 + WebView + 업데이트 확인, 단일 화면)
- `update` — GitHub Releases 기반 앱 내 업데이트 확인/다운로드/설치
- `utils` — 로깅

## 인라인 번역 동작 방식

1. 사용자가 URL을 입력하거나 사이트 안의 링크를 클릭하면 WebView가 해당 페이지를 로드
2. `onPageFinished`에서 `PageTranslator.onPageLoaded()`가 `inline_translate.js`를 주입
3. JS가 `p, li, h1~h6, td, th, blockquote` 등 블록 레벨 요소를 순회해 각 요소에
   `data-tapp-id`를 부여하고, 요소의 `textContent` 전체를 `{id, text}` 목록으로 묶어
   JSON으로 Kotlin에 전달 (`TranslateAppBridge.onTextsCollected`). 텍스트 노드 하나하나가
   아니라 블록 전체를 단위로 삼는 이유는, `<b>`/`<a>` 같은 인라인 태그로 문장이 쪼개져
   있을 때 조각마다 따로 번역하면 문맥이 끊겨 품질이 떨어지기 때문이다(예: 일본어 문장
   중간에 영어 고유명사가 별도 태그로 있으면 그 부분만 따로 번역되어 어색하게 섞여 보임).
4. Kotlin이 블록 단위 캐시를 확인하고, 없으면 ML Kit로 블록 전체를 한 번에 번역 후 캐시에 저장
5. 번역 결과를 `{id: 번역문}` 형태로 JS에 다시 넘기면, JS가 저장해둔 블록 요소
   참조(`__tappBlockRefs`)를 통해 그 블록의 `textContent` 전체를 번역문으로 치환.
   블록 안의 인라인 태그(강조, 블록 내부 링크 등) 구조는 사라지고 순수 텍스트로 바뀐다.
6. 사이트의 `<a>`, `<button>`, `<input>` 등 블록 자체를 감싸는 요소는 전혀 건드리지
   않으므로 클릭/입력이 원본 사이트와 동일하게 동작하고, 새로 로드되는 페이지도
   2번부터 다시 반복된다.

## 언어 선택

상단의 출발어/도착어 드롭다운(`spinnerSourceLang` / `spinnerTargetLang`)에서 언어 쌍을
바꿀 수 있다. 지원 언어 목록은 `translation/SupportedLanguages.kt`에 정의되어 있고
(한국어/영어/일본어/중국어/프랑스어/독일어/스페인어/러시아어/베트남어/태국어), ML Kit이
지원하는 다른 언어를 추가하고 싶으면 이 파일에 `LanguageOption(표시이름, BCP-47 코드)`을
추가하기만 하면 된다.

언어를 바꾸면 현재 로드된 페이지를 새로고침해 원문부터 다시 가져와 새 언어 쌍으로
재번역한다(`PageTranslator.retranslateCurrentPage()`). 기본값은 영어 → 한국어.

## 번역 품질 높이기 (Google Cloud Translation, 선택)

기본값인 ML Kit 온디바이스 번역은 무료·오프라인이지만 문장이 길어지면 직역/의역이
뒤섞여 다소 부자연스러울 수 있다. `local.properties`에 아래 한 줄을 추가하면 그 대신
Google Cloud Translation API(구글 번역과 같은 계열의 서버급 모델)를 쓴다.

```properties
GOOGLE_TRANSLATE_API_KEY=본인의_API_키
```

- 키 발급: https://console.cloud.google.com 에서 프로젝트를 만들고 "Cloud Translation
  API"를 활성화한 뒤 사용자 인증 정보에서 API 키 생성. 월 50만 자까지 무료, 이후 과금.
- `local.properties`는 `.gitignore`에 이미 포함되어 있어 커밋되지 않는다.
- **이 키를 넣지 않고 로컬에서 빌드하거나, GitHub Actions로 자동 배포되는 공개
  릴리즈 APK를 그대로 쓰면** 이 파일 자체가 CI에 없으므로 키가 빈 값으로 빌드되고,
  앱은 자동으로 ML Kit 온디바이스 번역으로 동작한다 (`FallbackTranslator`가 처리).
  즉 키를 설정하는 건 순전히 선택 사항이며, 설정한 사람만 더 자연스러운 번역을 얻는다.

## 빌드 전 필요한 것

1. JDK 17
2. Android SDK (Command line tools만 설치해도 무방, platform-tools + platforms;android-34 + build-tools;34.0.0)
3. `local.properties`의 `sdk.dir`을 본인 SDK 경로로 수정

## Gradle Wrapper에 대해

이 저장소에는 `gradle-wrapper.properties`만 포함되어 있고, 실행 스크립트(`gradlew`, `gradlew.bat`)와
`gradle-wrapper.jar`는 포함되어 있지 않습니다. 아래 둘 중 하나로 생성하세요.

- 로컬에 Gradle이 설치되어 있다면: `gradle wrapper --gradle-version 8.7`
- 또는 이 프로젝트를 Android Studio로 한 번 열면 자동 생성됩니다 (이후엔 다시 VS Code로 작업 가능)

## 빌드 & 실행

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 배포용(release) APK 빌드

```bash
./gradlew assembleRelease
```

`app/build.gradle.kts`의 `signingConfigs`에 본인 keystore를 등록한 뒤 빌드하면
서명된 APK가 `app/build/outputs/apk/release/`에 생성됩니다. (이 템플릿에는
개인 keystore가 없으므로 signingConfig는 직접 추가해야 합니다.)

## 앱 내 업데이트(GitHub Releases 기반)

Play Store 없이도 앱 안에서 "업데이트 확인" 버튼으로 새 버전을 받을 수 있다.
`update/AppUpdateChecker.kt`가 GitHub Releases API(`/releases/latest`)를 호출해
현재 설치된 `versionCode`보다 새 버전이 있으면 APK를 다운로드하고 설치 화면을 띄운다.

### 새 버전 배포 절차 (GitHub Actions 자동 빌드)

`.github/workflows/release.yml`이 `v*` 형태의 태그가 push되면 자동으로
release APK를 빌드하고 서명한 뒤 GitHub Release로 올린다. 로컬에 Android Studio가
없어도 태그만 push하면 된다.

1. `app/build.gradle.kts`에서 `versionCode`를 올리고 `versionName`도 갱신 후 커밋
2. **태그 이름에 반드시 versionCode와 같은 숫자가 포함되어야 한다**
   (예: versionCode 2라면 태그는 `v2`). 워크플로우가 이 일치 여부를 빌드 전에 검증하고,
   틀리면 빌드를 실패시킨다.
3. 태그를 push

```bash
git tag v2
git push origin v2
```

4. Actions 탭에서 빌드가 끝나면 Release가 자동 생성되고 APK가 첨부된다.

이렇게 올리면 기존 사용자가 앱에서 "업데이트 확인"을 누르는 순간 새 버전을 감지하고
다운로드/설치를 제안한다.

### 최초 1회 설정 — release keystore를 GitHub Secrets에 등록

서명 키가 버전마다 바뀌면 기존 앱 위에 업데이트 설치가 안 되므로(재설치 필요),
keystore는 한 번 만들어서 계속 재사용해야 한다. 저장소 Settings → Secrets and variables →
Actions에 아래 4개를 등록한다.

| Secret 이름 | 값 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | keystore 파일을 base64 인코딩한 문자열 |
| `RELEASE_KEYSTORE_PASSWORD` | keystore 비밀번호 |
| `RELEASE_KEY_ALIAS` | 키 별칭 |
| `RELEASE_KEY_PASSWORD` | 키 비밀번호 (PKCS12는 keystore 비밀번호와 동일) |

keystore 파일 자체와 비밀번호는 **절대 저장소에 커밋하지 않는다.** 로컬에서 새로 만들려면:

```bash
keytool -genkeypair -v -keystore release.keystore -alias translateapp \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.keystore   # 이 출력값을 RELEASE_KEYSTORE_BASE64에 등록
```

## 다음 확장 방향

- 언어 자동 감지 (현재는 드롭다운에서 수동 선택, 기본값 en→ko)
- ML Kit → NLLB-200 등 커스텀 온디바이스 모델로 교체 (translation/ 폴더에 새 구현체만 추가하면 됨)
- 텍스트가 늦게 로드되는 SPA/무한스크롤 사이트 대응 (MutationObserver로 새로 추가되는
  블록도 감지해 번역하는 방식으로 `inline_translate.js` 확장)
- 번역 중 원문이 잠깐 보이는 깜빡임(FOUC) 완화 (예: 번역 전 텍스트 살짝 흐리게 표시)
- 블록 안 인라인 링크/강조 태그를 보존하면서 번역하는 방식 (현재는 블록 전체를
  순수 텍스트로 치환하므로 블록 내부 링크의 클릭 가능 영역이 사라짐)
