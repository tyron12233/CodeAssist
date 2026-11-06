#!/bin/bash
# Compiles the pure-JVM part of ZipCLI (domain + zip + filesystem) and runs the standalone test programs.
# Needs kotlinc on PATH and a tiny kotlinx.coroutines stub when the real library is not on the classpath:
#   CORO=path/to/kotlinx-coroutines-core-jvm.jar  (or leave unset to use tests/stubs/Coroutines.kt)
set -e
cd "$(dirname "$0")/.."
S=src/main/kotlin/com/zipcli/ke
OUT=$(mktemp -d)
FILES=$(find $S/domain $S/zip -name '*.kt' | grep -v 'SafCommands\|McpCommands')
STD=$(dirname "$(dirname "$(readlink -f "$(which kotlinc)")")")/lib/kotlin-stdlib.jar
for t in ScenarioTests MultilineTests RecommendationTests TranscriptTests; do
  mkdir -p $OUT/$t
  if [ -n "$CORO" ]; then EXTRA="-cp $CORO"; STUB=""; else EXTRA=""; STUB=tests/stubs/Coroutines.kt; fi
  kotlinc -nowarn $EXTRA -d $OUT/$t $STUB $FILES $S/fs/FileEntry.kt $S/fs/NioFileSystem.kt $S/fs/VirtualPath.kt $S/fs/TerminalFileSystem.kt tests/$t.kt 2>&1 | grep -v '^warning' || true
  echo "== $t"
  if [ $t = TranscriptTests ]; then java -cp "$OUT/$t:$STD${CORO:+:$CORO}" TranscriptTestsKt tests/transcripts
  else java -cp "$OUT/$t:$STD${CORO:+:$CORO}" ${t}Kt; fi
done
