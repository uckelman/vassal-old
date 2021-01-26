#!/bin/sh
echo Creating Vengine.jar file...
rm -f lib/Vengine.jar
jar cmf manifest.mf lib/Vengine.jar -C bin .
echo Setting up VASSAL distribution directory "/c/temp/VASSAL"
rm -rf /c/temp/VASSAL
mkdir /c/temp/VASSAL
cp -r lib /c/temp/VASSAL
cp dist/windows/VASSAL.* /c/temp/VASSAL
cp lib-nondist/*.jar /c/temp/VASSAL/lib
cp src/logback.xml /c/temp/VASSAL
cp src/vassal.xml /c/temp/VASSAL
cp -r doc /c/temp/VASSAL
cp VASSAL_server.bat /c/temp/VASSAL
echo Changing directories
pushd /c/temp/ 
echo Zipping up directory for distribution
zip -r -q VASSAL.zip VASSAL
echo Moving back to original directory
popd
echo Done


