#!/bin/bash
# iPhone için Release derlemesi, imzasız .ipa olarak paketlenir.
# (AltStore / SideStore gibi araçlar kurarken kendi Apple kimliğinle imzalar.)
set -euo pipefail
xcodebuild -project Devamsizlik.xcodeproj -scheme Devamsizlik -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' -derivedDataPath build/device \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" build -quiet
rm -rf Payload Devamsizlik-imzasiz.ipa
mkdir Payload
cp -R build/device/Build/Products/Release-iphoneos/Devamsizlik.app Payload/
zip -qry Devamsizlik-imzasiz.ipa Payload
ls -la Devamsizlik-imzasiz.ipa
