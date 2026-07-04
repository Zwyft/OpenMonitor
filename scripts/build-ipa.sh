#!/usr/bin/env bash
set -euo pipefail

SCHEME="${SCHEME:-OpenMonitor}"
PROJECT="${PROJECT:-OpenMonitor.xcodeproj}"
CONFIGURATION="${CONFIGURATION:-Release}"
ARCHIVE_PATH="${ARCHIVE_PATH:-build/OpenMonitor.xcarchive}"
EXPORT_PATH="${EXPORT_PATH:-build/ipa}"
EXPORT_OPTIONS="${EXPORT_OPTIONS:-build/ExportOptions.plist}"
METHOD="${METHOD:-development}"
SIGNING_STYLE="${SIGNING_STYLE:-automatic}"
DEVELOPMENT_TEAM="${DEVELOPMENT_TEAM:-}"
CODE_SIGN_IDENTITY="${CODE_SIGN_IDENTITY:-}"
PROVISIONING_PROFILE_SPECIFIER="${PROVISIONING_PROFILE_SPECIFIER:-}"
APP_BUNDLE_IDENTIFIER="${APP_BUNDLE_IDENTIFIER:-com.no3.OpenMonitor}"

mkdir -p "$(dirname "$ARCHIVE_PATH")" "$EXPORT_PATH" "$(dirname "$EXPORT_OPTIONS")"

{
  cat <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>method</key>
	<string>${METHOD}</string>
	<key>signingStyle</key>
	<string>${SIGNING_STYLE}</string>
	<key>stripSwiftSymbols</key>
	<true/>
	<key>compileBitcode</key>
	<false/>
EOF

  if [[ "$SIGNING_STYLE" == "manual" ]]; then
    if [[ -z "$PROVISIONING_PROFILE_SPECIFIER" ]]; then
      echo "PROVISIONING_PROFILE_SPECIFIER is required when SIGNING_STYLE=manual" >&2
      exit 1
    fi

    cat <<EOF
	<key>provisioningProfiles</key>
	<dict>
		<key>${APP_BUNDLE_IDENTIFIER}</key>
		<string>${PROVISIONING_PROFILE_SPECIFIER}</string>
	</dict>
EOF
  fi

  cat <<EOF
</dict>
</plist>
EOF
} > "$EXPORT_OPTIONS"

XCODEBUILD_ARGS=(
  -project "$PROJECT"
  -scheme "$SCHEME"
  -configuration "$CONFIGURATION"
  -destination "generic/platform=iOS"
  -archivePath "$ARCHIVE_PATH"
)

if [[ -n "$DEVELOPMENT_TEAM" ]]; then
  XCODEBUILD_ARGS+=("DEVELOPMENT_TEAM=${DEVELOPMENT_TEAM}")
fi

if [[ -n "$CODE_SIGN_IDENTITY" ]]; then
  XCODEBUILD_ARGS+=("CODE_SIGN_IDENTITY=${CODE_SIGN_IDENTITY}")
fi

if [[ -n "$PROVISIONING_PROFILE_SPECIFIER" ]]; then
  XCODEBUILD_ARGS+=("PROVISIONING_PROFILE_SPECIFIER=${PROVISIONING_PROFILE_SPECIFIER}")
fi

XCODEBUILD_ARGS+=("CODE_SIGN_STYLE=${SIGNING_STYLE}")

xcodebuild "${XCODEBUILD_ARGS[@]}" -allowProvisioningUpdates archive

xcodebuild \
  -exportArchive \
  -archivePath "$ARCHIVE_PATH" \
  -exportPath "$EXPORT_PATH" \
  -exportOptionsPlist "$EXPORT_OPTIONS" \
  -allowProvisioningUpdates

echo "IPA exported to: $EXPORT_PATH"
