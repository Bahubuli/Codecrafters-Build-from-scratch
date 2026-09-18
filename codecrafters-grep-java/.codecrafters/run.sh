#!/bin/sh
#
# This script is used to run your program on CodeCrafters
#
# This runs after .codecrafters/compile.sh
#
# Learn more: https://codecrafters.io/program-interface

set -e # Exit on failure

if [ -t 1 ]; then
  export IS_TTY="true"
else
  export IS_TTY="false"
fi

exec java --enable-preview -jar /tmp/codecrafters-build-grep-java/codecrafters-grep.jar "$@"
