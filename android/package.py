import sys, zipfile, os

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
if os.path.exists(out):
    os.remove(out)
zin = zipfile.ZipFile(base, 'r')
zout = zipfile.ZipFile(out, 'w')
try:
    for item in zin.infolist():
        data = zin.read(item.filename)
        zout.writestr(item, data, zipfile.ZIP_DEFLATED)
    zout.write(dex, 'classes.dex', zipfile.ZIP_DEFLATED)
finally:
    zin.close()
    zout.close()
print('packaged ->', out, os.path.getsize(out), 'bytes')
