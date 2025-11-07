# Custom Browser for Galaxy Tab

Android WebView 기반 커스텀 브라우저로, AI 서비스와 로컬 웹앱에 최적화되어 있습니다.

## 주요 기능

### ✨ 핵심 기능
- **AI 서비스 빠른 접근**: Claude, Gemini, ChatGPT 원터치 접속
- **OAuth 자동 로그인**: WebView 쿠키/세션 영구 저장으로 로그인 상태 유지
- **GitHub 통합**: 코드 보관 및 다양한 AI 모델에서 접근
- **북마크 시스템**: 자주 방문하는 페이지 저장 및 관리
- **로컬 웹앱 지원**: localhost 포트 설정으로 로컬 개발 서버 접근
- **갤럭시 탭 최적화**: 태블릿 화면에 최적화된 UI/UX

### 🚀 추가 기능
- **Kiwi Browser 통합**: 크롬 익스텐션 필요시 원터치로 Kiwi Browser 실행
- **다운로드 관리자**: 파일 다운로드 및 시스템 다운로드 폴더 접근
- **영구 세션 저장**: OpenAI, GitHub, Google 로그인 자동 유지
- Desktop 모드 전환
- 완전한 WebView 브라우저 기능 (뒤로/앞으로 가기)
- 진행 상태 표시
- URL 검색창 (Google 검색 통합)
- 북마크 관리 (추가/삭제)
- 다운로드 알림 (완료 시 알림 표시)

## 프로젝트 구조

```
custombrowser/
├── app/
│   ├── src/main/
│   │   ├── java/com/custombrowser/
│   │   │   ├── MainActivity.kt          # 메인 브라우저 액티비티
│   │   │   ├── Bookmark.kt              # 북마크 데이터 클래스
│   │   │   └── BookmarkManager.kt       # 북마크 관리 시스템
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   └── activity_main.xml    # 메인 레이아웃
│   │   │   ├── values/
│   │   │   │   ├── strings.xml
│   │   │   │   ├── colors.xml
│   │   │   │   └── themes.xml
│   │   │   └── mipmap-*/                # 앱 아이콘
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── build.gradle
├── settings.gradle
└── README.md
```

## 빌드 방법

### 요구사항
- Android Studio Hedgehog | 2023.1.1 이상
- Android SDK 34
- Gradle 8.2
- JDK 17

### 빌드 단계

1. **프로젝트 클론**
   ```bash
   git clone <repository-url>
   cd custombrowser
   ```

2. **Android Studio에서 열기**
   - Android Studio 실행
   - "Open an Existing Project" 선택
   - custombrowser 폴더 선택

3. **의존성 동기화**
   - Gradle이 자동으로 의존성을 다운로드합니다
   - 오류 발생 시 "File > Sync Project with Gradle Files" 실행

4. **빌드 및 실행**
   - 상단 툴바에서 "Run" 버튼 클릭
   - 또는 `Shift + F10` (Windows/Linux) / `Control + R` (Mac)

### 명령줄 빌드

```bash
# Debug APK 빌드
./gradlew assembleDebug

# Release APK 빌드 (서명 필요)
./gradlew assembleRelease

# APK 설치
./gradlew installDebug
```

빌드된 APK 위치: `app/build/outputs/apk/debug/app-debug.apk`

## 사용 방법

### AI 서비스 접근 및 OAuth 로그인
상단 퀵 액세스 버튼 클릭:
- **Claude**: Claude.ai 접속 (OAuth 로그인 자동 저장)
- **Gemini**: Google Gemini 접속 (Google 로그인 자동 저장)
- **ChatGPT**: ChatGPT 접속 (OpenAI 로그인 자동 저장)
- **GitHub**: GitHub 접속 (코드 보관 및 AI 모델 연동용)
- **Localhost**: 로컬 개발 서버 접속 (포트 입력)

**OAuth 자동 로그인:**
- 한 번 로그인하면 WebView 쿠키/세션이 영구 저장됨
- 앱 재시작 후에도 로그인 상태 유지
- ChatGPT, GitHub, Gemini 등 자동 로그인

### 북마크 관리
1. 원하는 페이지에서 **별 아이콘** 클릭하여 북마크 추가
2. 메뉴(⋮) > Bookmarks에서 저장된 북마크 확인
3. 북마크 이름 클릭하여 즉시 이동
4. "Manage" 버튼으로 북마크 삭제 가능

### 로컬 웹앱 접근
1. **Localhost** 버튼 클릭
2. 포트 번호 입력 (예: 3000, 8080)
3. 자동으로 `http://localhost:{port}` 접속

### Desktop 모드
- 메뉴(⋮) > Desktop Mode 선택
- PC 버전 웹사이트 렌더링

### 다운로드
- 웹페이지에서 파일 다운로드 시 자동으로 Android DownloadManager 사용
- 메뉴(⋮) > Downloads에서 다운로드 폴더 열기
- 다운로드 완료 시 알림으로 확인

### Kiwi Browser 통합
Quick Access 영역의 **Kiwi 🦄** 버튼 또는 메뉴(⋮) > Open in Kiwi Browser 선택:
- **Kiwi 설치됨**: 현재 페이지가 Kiwi Browser에서 열림
  - Kiwi에서 크롬 익스텐션 사용 가능
  - Chrome Web Store 접근 가능
- **Kiwi 미설치**: Play Store 설치 안내 다이얼로그 표시
  - "Install" 버튼으로 직접 Play Store 이동

**사용 시나리오:**
1. 일반 브라우징: 가벼운 Custom Browser 사용
2. 익스텐션 필요시: Kiwi 버튼으로 즉시 전환
3. 최상의 경험: 두 브라우저의 장점 활용

### GitHub 통합
**GitHub** 버튼으로 빠른 접속:
- 코드 저장소 관리
- 여러 AI 모델(Claude, ChatGPT, Gemini)에서 GitHub 코드 접근
- OAuth 로그인 상태 자동 저장
- 웹 IDE (github.dev) 접근 가능

**활용 방법:**
1. GitHub 버튼으로 로그인
2. 저장소에 코드 커밋/푸시
3. Claude/ChatGPT에서 GitHub 저장소 URL 공유
4. AI가 코드 분석/수정/리뷰 수행

## 설정 커스터마이징

### Quick Access URL 변경
1. 메뉴(⋮) > Settings 선택
2. 원하는 서비스 URL 편집
3. Save 버튼으로 저장

기본 URL:
- Claude: `https://claude.ai/new`
- Gemini: `https://gemini.google.com/app`
- ChatGPT: `https://chatgpt.com`
- GitHub: `https://github.com`
- Localhost: `http://localhost:3000`

## 갤럭시 탭 최적화

이 브라우저는 갤럭시 탭을 위해 다음과 같이 최적화되었습니다:

- **큰 터치 영역**: 버튼과 컨트롤이 태블릿 사용에 적합한 크기
- **가로 모드 지원**: 회전 시 자동 레이아웃 조정
- **줌 컨트롤**: 핀치 줌 및 더블 탭 줌 지원
- **와이드 뷰포트**: 태블릿 화면을 최대한 활용

## 기술 스택

- **언어**: Kotlin
- **최소 SDK**: 24 (Android 7.0 Nougat)
- **타겟 SDK**: 34 (Android 14)
- **핵심 라이브러리**:
  - AndroidX WebKit
  - Material Components
  - Gson (북마크 저장)

## 보안 및 권한

### 필요한 권한
- `INTERNET`: 웹 페이지 로딩
- `ACCESS_NETWORK_STATE`: 네트워크 상태 확인
- `POST_NOTIFICATIONS`: 다운로드 완료 알림 (Android 13+)
- `WRITE_EXTERNAL_STORAGE`: 다운로드 파일 저장 (Android 9 이하)

### 보안 기능
- Mixed Content 지원 (HTTPS/HTTP)
- Cleartext Traffic 허용 (localhost용)
- JavaScript 활성화 (모던 웹앱 지원)

## 향후 개발 계획

- [ ] 탭 브라우징 지원
- [ ] 히스토리 기능
- [x] 다운로드 관리자 ✅
- [x] Kiwi Browser 통합 (하이브리드 접근) ✅
- [ ] 다크 모드 테마
- [ ] 북마크 폴더/카테고리
- [ ] 동기화 기능 (클라우드)

## 크롬 익스텐션 지원에 대하여

### ✅ 현재 구현: 옵션 B (하이브리드 접근)

이 브라우저는 **하이브리드 방식**을 채택하여 크롬 익스텐션을 지원합니다:

**장점:**
- ✅ **가벼움**: 기본 WebView로 빠른 브라우징
- ✅ **익스텐션 지원**: 필요시 Kiwi Browser로 원터치 전환
- ✅ **유연함**: 두 브라우저의 장점 활용
- ✅ **쉬운 사용**: Quick Access에서 즉시 전환

**작동 방식:**
1. Custom Browser (WebView): 일반 브라우징, AI 서비스, 로컬 웹앱
2. Kiwi Browser 전환: 익스텐션 필요시 Kiwi 버튼 클릭
3. Kiwi Browser에서: 모든 크롬 익스텐션 사용 가능

### 대안 옵션들

**옵션 A: Kiwi Browser 전체 포크** (미구현)
- Chromium 소스 빌드 필요
- 빌드 시간 매우 길음 (수 시간)
- 복잡도 높음
- 📝 필요시 별도 구현 가능

**옵션 C: UserScript 인젝션** (미구현)
- JavaScript 인젝션으로 유사 기능
- Tampermonkey/Greasemonkey 스타일
- 제한적이지만 가벼움
- 📝 필요시 추가 가능

## 라이선스

이 프로젝트는 개인 사용을 위한 커스텀 브라우저입니다.

## 문제 해결

### 빌드 오류
- Gradle 버전 확인: `./gradlew --version`
- Clean 후 재빌드: `./gradlew clean build`
- SDK 경로 확인: `local.properties` 파일 확인

### 런타임 오류
- 인터넷 권한 확인
- WebView 크래시 시 Chrome WebView 업데이트

### 로컬 서버 접근 불가
- `AndroidManifest.xml`의 `usesCleartextTraffic="true"` 확인
- 방화벽/보안 소프트웨어 설정 확인
- 포트 번호 정확성 확인

## 기여

이슈 및 개선 제안은 언제든 환영합니다!

---

**Made with ❤️ for Galaxy Tab Users**
