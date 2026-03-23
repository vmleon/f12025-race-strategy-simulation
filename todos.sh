#!/usr/bin/env bash
set -euo pipefail

MAX_RETRIES=2
RETRY_WAIT=600          # 10 minutes between retries
RATE_LIMIT_WAIT=900     # 15 minutes if rate-limited / out of tokens
COOLDOWN=30             # 30 seconds between successful todos
TODOS=(03 18 01 02 06 04 05 07 10 11 08 09 21 13 19 12 14 15 17 22 16 23 20)
OUTPUT_FILE=$(mktemp)

wait_with_countdown() {
  local seconds=$1
  local reason=$2
  echo "⏳ Waiting ${seconds}s ($reason)..."
  while [ $seconds -gt 0 ]; do
    printf "\r   %02d:%02d remaining..." $((seconds / 60)) $((seconds % 60))
    sleep 1
    seconds=$((seconds - 1))
  done
  printf "\r   Done waiting.          \n"
}

check_output_for_errors() {
  local file=$1
  if grep -qi "rate limit\|rate_limit\|429\|too many requests\|overloaded\|capacity" "$file" 2>/dev/null; then
    return 1  # rate limited
  fi
  if grep -qi "out of tokens\|token limit\|quota exceeded\|billing\|insufficient_quota" "$file" 2>/dev/null; then
    return 2  # out of tokens
  fi
  return 0
}

for todo in "${TODOS[@]}"; do
  todo_file="todos/TODO-${todo}.md"
  attempt=0

  while [ $attempt -le $MAX_RETRIES ]; do
    if [ ! -f "$todo_file" ]; then
      echo "✓ Todo $todo already completed (file deleted). Skipping."
      break
    fi

    if [ $attempt -gt 0 ]; then
      wait_with_countdown $RETRY_WAIT "retry cooldown"
      echo "⟳ Retrying todo $todo (attempt $((attempt + 1))/$((MAX_RETRIES + 1)))..."
    else
      echo "▶ Starting todo $todo..."
    fi

    claude --dangerously-skip-permissions -p \
      "Implement todo $todo following the workflow in todos/00-IMPLEMENTATION-ORDER.md. Read the todo file at $todo_file, implement it, run tests, write the summary, update affected todos, delete the todo file, and commit." \
      > "$OUTPUT_FILE" 2>&1 || true

    # Check for rate limit / token errors in output
    check_output_for_errors "$OUTPUT_FILE"
    error_type=$?

    if [ $error_type -eq 1 ]; then
      echo "⚠ Rate limit detected. Waiting longer before retry..."
      wait_with_countdown $RATE_LIMIT_WAIT "rate limit recovery"
    elif [ $error_type -eq 2 ]; then
      echo "⛔ Token/quota limit reached. Output:"
      tail -20 "$OUTPUT_FILE"
      echo ""
      echo "Pausing. Re-run this script to resume from todo $todo."
      rm -f "$OUTPUT_FILE"
      exit 2
    fi

    if [ ! -f "$todo_file" ]; then
      echo "✓ Todo $todo completed successfully."
      break
    else
      echo "✗ Todo $todo file still exists after attempt $((attempt + 1))."
      tail -5 "$OUTPUT_FILE"
      attempt=$((attempt + 1))
    fi
  done

  if [ -f "$todo_file" ]; then
    echo "✗✗ Todo $todo FAILED after $((MAX_RETRIES + 1)) attempts. Stopping."
    echo "Last output:"
    tail -20 "$OUTPUT_FILE"
    rm -f "$OUTPUT_FILE"
    exit 1
  fi

  # Brief cooldown between todos to avoid back-to-back hammering
  if [ "$todo" != "24" ]; then
    wait_with_countdown $COOLDOWN "cooldown between todos"
  fi
done

rm -f "$OUTPUT_FILE"
echo "All todos completed."
