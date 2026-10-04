#!/usr/bin/env sh
DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  exec java -classpath "$DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "Gradle 9.6.1 is required. Open this project in Android Studio/AndroidIDE or install Gradle 9.6.1." >&2
exit 1
