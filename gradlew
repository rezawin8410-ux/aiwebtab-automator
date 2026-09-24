#!/bin/sh
#
# Copyright © 2015-2021 the original authors.
#
APP_NAME="Gradle"
APP_HOME=$( cd "$(dirname "$0")" ; pwd -P )
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
exec java -Xmx64M -Xms64M -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
