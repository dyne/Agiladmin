#!/bin/bash
# update-pocketid.sh

set -e

# --- Configuration ---
INSTALL_DIR="/home/pocketid"
SERVICE_NAME="pocketid.service"
USER="pocketid"
GROUP="pocketid"
VERSION_FILE="${INSTALL_DIR}/version.txt"
ARCHITECTURE="amd64" # Change if needed (e.g., arm64)
# --- End Configuration ---

>&2 echo "Checking for the latest version of PocketID..."
LATEST_TAG_JSON=$(curl -s https://api.github.com/repos/pocket-id/pocket-id/releases/latest)
LATEST_TAG=$(echo "$LATEST_TAG_JSON" | grep '"tag_name":' | sed -E 's/.*"v([^"]+)".*/\1/') # Version without 'v'
LATEST_TAG_WITH_V=$(echo "$LATEST_TAG_JSON" | grep '"tag_name":' | sed -E 's/.*"([^"]+)".*/\1/') # Version with 'v'

if [ -z "$LATEST_TAG" ]; then
    >&2 echo "Could not retrieve the latest version from GitHub."
    exit 1
fi

>&2 echo "Latest version available: v${LATEST_TAG}"

CURRENT_VERSION="0" # Default to 0 if no version file
if [ -f "$VERSION_FILE" ]; then
    CURRENT_VERSION=$(cat "$VERSION_FILE")
fi
>&2 echo "Currently installed version: v${CURRENT_VERSION}"

if [ "$LATEST_TAG" = "$CURRENT_VERSION" ]; then
    >&2 echo "PocketID is already up to date (v${CURRENT_VERSION})."
    exit 0
fi

>&2 echo "New version v${LATEST_TAG} available. Updating..."

DOWNLOAD_URL=$(echo "$LATEST_TAG_JSON" | grep -E "browser_download_url.*pocket-id-linux-${ARCHITECTURE}" | cut -d '"' -f 4)

if [ -z "$DOWNLOAD_URL" ]; then
    >&2 echo "Could not find the download URL for linux-${ARCHITECTURE} and version v${LATEST_TAG}."
    exit 1
fi

>&2 echo "Stopping service ${SERVICE_NAME}..."
sudo systemctl stop "${SERVICE_NAME}"

>&2 echo "Backing up the old binary..."
BACKUP_NAME="pocket-id_backup_v${CURRENT_VERSION}_$(date +%Y%m%d_%H%M%S)"
sudo cp "${INSTALL_DIR}/pocket-id" "${INSTALL_DIR}/${BACKUP_NAME}"
>&2 echo "Old binary backed up to ${INSTALL_DIR}/${BACKUP_NAME}"
