#!/bin/bash

# Check if a directory is provided
if [ -z "$1" ]; then
  echo "Usage: $0 <directory>"
  exit 1
fi

# The directory to search for files
SEARCH_DIR=$1
# Output file
OUTPUT_FILE="source_dump.md"

# Clear the output file if it exists
> "$OUTPUT_FILE"

# Find all specified file types, excluding files under 'target', '.idea', and '.github' directories, and process them
find "$SEARCH_DIR" \( \
  -name "*.java" -o \
  -name "*.xml" -o \
  -name "*.config" -o \
  -name "Dockerfile" -o \
  -name "*.yml" -o \
  -name "*.yaml" -o \
  -name "*.properties" -o \
  -name "*.sh" -o \
  -name "*.sql" \
\) -not -path "*/target/*" -not -path "*/.idea/*" -not -path "*/.github/*" -not -path "*/reference/*" | while read -r file; do
  echo "Processing $file..."
  # Append the file path as a header
  echo "# \`$file\`" >> "$OUTPUT_FILE"

  # Determine the language for the code block
  if [[ "$file" == *.java ]]; then
    LANG="java"
  elif [[ "$file" == *.xml ]]; then
    LANG="xml"
  elif [[ "$file" == *.yml || "$file" == *.yaml ]]; then
    LANG="yaml"
  elif [[ "$file" == *.properties || "$file" == *.config ]]; then
    LANG="properties"
  elif [[ "$file" == *.sh ]]; then
    LANG="bash"
  elif [[ "$file" == *Dockerfile ]]; then
    LANG="dockerfile"
  elif [[ "$file" == *.sql ]]; then
    LANG="sql"
  else
    LANG="" # Default or unknown language
  fi

  # Read file content into a variable
  file_content=$(cat "$file")

  # Append the file content in a code block
  echo "\`\`\`$LANG" >> "$OUTPUT_FILE"
  echo "$file_content" >> "$OUTPUT_FILE"
  echo "\`\`\`" >> "$OUTPUT_FILE"
  echo "" >> "$OUTPUT_FILE"
done

echo "Markdown file '$OUTPUT_FILE' created successfully."