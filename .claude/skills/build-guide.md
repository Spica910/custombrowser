# Custom Browser - Termux 빌드 가이드

## 📋 빌드 환경

### 필수 요구사항
- **Termux** (Android Terminal Emulator)
- **JDK 11 이상** (OpenJDK 21 권장)
- **Gradle 9.2.0 이상**
- **Android SDK** (설치 경로: `/data/data/com.termux/files/home/opt/android-sdk`)
- **Build Tools 35.0.0** (AAPT2 포함)

---

## 🚀 빠른 빌드 명령어

### 전체 빌드 (클린 + 디버그 APK)
```bash
export ANDROID_SDK_ROOT=/data/data/com.termux/files/home/opt/android-sdk && gradle clean assembleDebug --no-daemon
```

### 증분 빌드 (작은 변경 후)
```bash
export ANDROID_SDK_ROOT=/data/data/com.termux/files/home/opt/android-sdk && gradle assembleDebug --no-daemon
```

---

## ⚙️ 핵심 설정 파일

### 1. gradle.properties
Termux 환경에 최적화된 설정:

```properties
# JVM 설정 (Termux 최적화)
org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8 -XX:+UseParallelGC -Dorg.gradle.java.home=/data/data/com.termux/files/usr

# Daemon 비활성화 (Termux 호환성)
org.gradle.daemon=false

# 병렬 처리 비활성화
org.gradle.parallel=false

# 캐시 비활성화
org.gradle.caching=false
org.gradle.configuration-cache=false

# AAPT2 경로 (중요!)
android.aapt2FromMavenOverride=/data/data/com.termux/files/home/opt/android-sdk/build-tools/35.0.0/aapt2

# AndroidX 사용
android.useAndroidX=true
android.enableJetifier=true
android.nonTransitiveRClass=true

# R8 설정
android.enableR8.fullMode=false
```

**⚠️ 주의사항:**
- `android.aapt2FromMavenOverride` 경로는 실제 build-tools 버전에 맞게 조정
- `org.gradle.daemon=false` 필수 (Termux에서 daemon 사용 시 오류)
- 메모리 제한: `-Xmx1536m` (기기 사양에 따라 조정 가능)

---

### 2. build.gradle.kts (루트)
```kotlin
// Top-level build file
buildscript {
    val kotlinVersion = "1.9.22"
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
```

**버전 호환성:**
- Gradle 9.2.0 ↔ Android Gradle Plugin 8.7.3
- Kotlin 1.9.22
- Java 11 (JVM Target)

---

### 3. app/build.gradle.kts
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.custombrowser"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.custombrowser"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false  // 🔥 중요! Java 컴파일 방지
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
```

**⚠️ 필수 설정:**
- `buildConfig = false` - BuildConfig.java 생성 방지
  - `true`로 설정 시 Java 컴파일 오류 발생 (jlink 관련)
- `jvmTarget = "11"` - Java 11 타겟팅
- Kotlin DSL 사용 (.gradle.kts)

---

## 🔧 문제 해결

### 1. "ClassNotFoundException: GradleWrapperMain"
**원인:** Gradle wrapper jar 파일 누락
**해결:**
```bash
# Gradle wrapper 재생성
gradle wrapper --gradle-version 8.2

# 또는 시스템 gradle 직접 사용
gradle assembleDebug --no-daemon
```

---

### 2. "HasConvention 오류"
**원인:** Gradle 9.x와 Android Gradle Plugin 버전 불일치
**해결:** AGP를 8.7.3 이상으로 업그레이드
```kotlin
classpath("com.android.tools.build:gradle:8.7.3")
```

---

### 3. "setWebContentsDebuggingEnabled 오류"
**원인:** WebView 인스턴스 메서드로 호출
**해결:** 정적 메서드로 변경
```kotlin
// ❌ 잘못된 사용
binding.webView.setWebContentsDebuggingEnabled(true)

// ✅ 올바른 사용
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
    WebView.setWebContentsDebuggingEnabled(true)
}
```

---

### 4. AAPT2 경로 오류
**원인:** build-tools 버전 불일치
**해결:**
```bash
# 설치된 build-tools 확인
ls $ANDROID_SDK_ROOT/build-tools/

# gradle.properties에서 경로 수정
android.aapt2FromMavenOverride=/data/data/com.termux/files/home/opt/android-sdk/build-tools/35.0.0/aapt2
```

---

### 5. 메모리 부족 오류
**원인:** JVM 힙 메모리 부족
**해결:** gradle.properties 수정
```properties
# 메모리 증가 (최대 2GB)
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8

# 또는 GC 최적화
org.gradle.jvmargs=-Xmx1536m -XX:+UseParallelGC -XX:MaxMetaspaceSize=512m
```

---

## 📦 빌드 결과

### 성공 시 출력
```
BUILD SUCCESSFUL in 24s
36 actionable tasks: 9 executed, 27 up-to-date
```

### APK 위치
```
app/build/outputs/apk/debug/app-debug.apk
```

### APK 정보 확인
```bash
# 크기 확인
ls -lh app/build/outputs/apk/debug/app-debug.apk

# APK 내용 확인
unzip -l app/build/outputs/apk/debug/app-debug.apk
```

---

## 🎯 릴리즈 빌드 (선택사항)

### 릴리즈 APK 빌드
```bash
export ANDROID_SDK_ROOT=/data/data/com.termux/files/home/opt/android-sdk && gradle assembleRelease --no-daemon
```

### 서명된 APK 만들기
1. 키스토어 생성:
```bash
keytool -genkey -v -keystore my-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias my-alias
```

2. app/build.gradle.kts에 서명 설정 추가:
```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("my-release-key.jks")
            storePassword = "password"
            keyAlias = "my-alias"
            keyPassword = "password"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
}
```

---

## 🚫 피해야 할 것들

1. ❌ `buildConfig = true` 사용
2. ❌ `org.gradle.daemon=true` 설정
3. ❌ Java 17+ 타겟팅 (Termux 호환성)
4. ❌ Gradle wrapper 없이 `./gradlew` 실행
5. ❌ AAPT2 경로 누락
6. ❌ `--parallel` 옵션 사용

---

## 📊 빌드 최적화 팁

### 첫 빌드 (느림)
```bash
# 의존성 다운로드 포함: 2-5분 소요
gradle clean assembleDebug --no-daemon
```

### 증분 빌드 (빠름)
```bash
# 변경된 파일만 컴파일: 10-30초 소요
gradle assembleDebug --no-daemon
```

### 캐시 정리 (문제 발생 시)
```bash
# Gradle 캐시 삭제
rm -rf ~/.gradle/caches/

# 프로젝트 빌드 폴더 삭제
gradle clean

# 재빌드
gradle assembleDebug --no-daemon
```

---

## 🔍 디버깅

### 상세 로그 출력
```bash
gradle assembleDebug --no-daemon --info
```

### 스택트레이스 출력
```bash
gradle assembleDebug --no-daemon --stacktrace
```

### 의존성 확인
```bash
gradle dependencies
```

### 사용 가능한 태스크 확인
```bash
gradle tasks --all
```

---

## ✅ 빌드 체크리스트

빌드 전 확인사항:

- [ ] Termux 설치 및 업데이트
- [ ] JDK 11 이상 설치
- [ ] Android SDK 설치 (`$ANDROID_SDK_ROOT` 설정)
- [ ] Build Tools 35.0.0 설치
- [ ] gradle.properties 설정 완료
- [ ] build.gradle.kts 파일 Kotlin DSL 변환
- [ ] `buildConfig = false` 설정 확인
- [ ] AAPT2 경로 정확성 확인

빌드 후 확인사항:

- [ ] "BUILD SUCCESSFUL" 메시지 확인
- [ ] APK 파일 존재 확인 (`app/build/outputs/apk/debug/`)
- [ ] APK 크기 정상 (5-10MB)
- [ ] 컴파일 경고 확인 (필요 시 수정)

---

## 📚 참고 자료

- [Android Gradle Plugin 릴리즈 노트](https://developer.android.com/studio/releases/gradle-plugin)
- [Gradle 버전 호환성](https://developer.android.com/studio/releases/gradle-plugin#updating-gradle)
- [Kotlin Gradle Plugin](https://kotlinlang.org/docs/gradle.html)
- [Termux Wiki](https://wiki.termux.com/)

---

**마지막 업데이트:** 2025-11-07
**테스트 환경:** Termux on Android 15, Gradle 9.2.0, AGP 8.7.3
