#!/bin/bash

echo "🏗️  Custom Browser APK Builder"
echo "================================"
echo ""

# Check if Android SDK is set
if [ -z "$ANDROID_HOME" ]; then
    echo "⚠️  ANDROID_HOME is not set!"
    echo "Please set ANDROID_HOME environment variable to your Android SDK path"
    echo "Example: export ANDROID_HOME=/path/to/Android/Sdk"
    exit 1
fi

echo "📦 Cleaning previous builds..."
./gradlew clean

echo ""
echo "🔨 Building Debug APK..."
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo ""
    echo "✅ Build successful!"
    echo ""
    echo "📲 APK Location:"
    echo "   app/build/outputs/apk/debug/app-debug.apk"
    echo ""
    echo "📊 APK Info:"
    ls -lh app/build/outputs/apk/debug/app-debug.apk
    echo ""
    echo "🚀 Installation options:"
    echo "   1. Copy APK to your Galaxy Tab"
    echo "   2. Or run: adb install -r app/build/outputs/apk/debug/app-debug.apk"
else
    echo ""
    echo "❌ Build failed!"
    echo "Check the error messages above"
    exit 1
fi
