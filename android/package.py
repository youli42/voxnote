import sys, zipfile, os

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
if os.path.exists(out):
    os.remove(out)
zin = zipfile.ZipFile(base, 'r')
zout = zipfile.ZipFile(out, 'w')
try:
    for item in zin.infolist():
        data = zin.read(item.filename)
        # Android 11+（targetSdk R+）要求 resources.arsc 以「不压缩」方式存储，
        # 否则安装直接失败：Failed parse during installPackageLI ... 4-byte boundary。
        # 不压缩后再交给 zipalign 做 4 字节对齐。
        method = zipfile.ZIP_STORED if item.filename == 'resources.arsc' else zipfile.ZIP_DEFLATED
        zout.writestr(item, data, method)
    zout.write(dex, 'classes.dex', zipfile.ZIP_DEFLATED)
finally:
    zin.close()
    zout.close()
print('packaged ->', out, os.path.getsize(out), 'bytes')
