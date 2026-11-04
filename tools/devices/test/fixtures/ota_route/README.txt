Fixtures of tools/devices/test/devices_test.sh, the horse_popular route of tools/devices/ota.py.
Each *.cases line: a case name, then the arguments split at white space; @W@ is the test's scratch dir,
@F@ this folder. devices_test.sh runs every case through tools/devices/ota.py and through the tools/ota
program itself, then compares stdout, stderr and the exit code byte for byte (cmp). The cases stop before any
key, image scan or delta_generator run, so they need nothing outside this folder.
