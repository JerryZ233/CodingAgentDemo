#!/bin/sh
set -eu
mkdir -p .classes
javac -encoding UTF-8 -cp /opt/junit/console.jar -d .classes src/*.java public-tests/*.java
java -jar /opt/junit/console.jar execute --disable-banner --class-path .classes --select-class RegressionTest --fail-if-no-tests
