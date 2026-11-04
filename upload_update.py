# -*- coding: utf-8 -*-
"""
豆包悬浮球 · 一键发版工具（自动更新服务器端）
功能：连接ecojia.cn服务器，自动探测网站目录，上传最新APK和version.json
用法：python upload_update.py <新APK路径> <versionCode> <versionName> <更新说明>
作者：军哥懂保 微信：xunijiayuan
"""
import sys
import json
import paramiko

HOST = "ecojia.cn"
USER = "ubuntu"
PASSWORD = "Lzp520520520."

# 网站根目录（Caddy静态服务路径，ubuntu可写）
WEB_ROOT_CANDIDATES = [
    "/opt/ecojia/sites",
    "/www/wwwroot/ecojia.cn",
    "/var/www/html",
]
SUB_DIR = "doubao-ball"


def sh(client, cmd):
    """执行远程命令并返回输出"""
    _, stdout, stderr = client.exec_command(cmd, timeout=30)
    out = stdout.read().decode("utf-8", "ignore").strip()
    err = stderr.read().decode("utf-8", "ignore").strip()
    return out, err


def find_web_root(client):
    """找网站根目录：/opt/ecojia/sites 优先（Caddy静态路径，ubuntu可写）"""
    for cand in WEB_ROOT_CANDIDATES:
        out, _ = sh(client, "test -d %s && echo EXISTS" % cand)
        if "EXISTS" in out:
            print("[探测] 网站目录: %s" % cand)
            return cand
    # 目录不存在就创建（/opt/ecojia/sites 首次）
    out, _ = sh(client, "echo '%s' | sudo -S sh -c 'mkdir -p /opt/ecojia/sites && chown ubuntu:ubuntu /opt/ecojia/sites' && echo OK" % PASSWORD)
    if "OK" in out:
        return "/opt/ecojia/sites"
    return None


def main():
    if len(sys.argv) < 5:
        print("用法: python upload_update.py <APK路径> <versionCode> <versionName> <更新说明>")
        sys.exit(1)
    apk_path, vcode, vname, changelog = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]

    print("[1/4] 连接服务器 %s ..." % HOST)
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=20)

    print("[2/4] 探测网站目录...")
    web_root = find_web_root(client)
    if not web_root:
        print("[失败] 没找到网站目录，请检查服务器web服务")
        sys.exit(1)

    target_dir = "%s/%s" % (web_root, SUB_DIR)

    # 生成version.json
    version_info = {
        "versionCode": vcode,
        "versionName": vname,
        "apkUrl": "https://ecojia.cn/%s/doubao-ball-latest.apk" % SUB_DIR,
        "changelog": changelog,
    }

    print("[3/4] 上传 APK 和 version.json ...")
    sh(client, "mkdir -p %s" % target_dir)
    sftp = client.open_sftp()
    sftp.put(apk_path, "%s/doubao-ball-latest.apk" % target_dir)
    with sftp.open("%s/version.json" % target_dir, "w") as f:
        f.write(json.dumps(version_info, ensure_ascii=False))
    sftp.close()
    # 校验文件确实到位
    out, _ = sh(client, "ls -la %s" % target_dir)
    print("    服务器文件: %s" % out.replace("\n", " | "))

    print("[4/4] 本地验证下载链接...")
    import urllib.request
    try:
        with urllib.request.urlopen(version_info["apkUrl"].replace("doubao-ball-latest.apk", "version.json"), timeout=15) as r:
            data = json.loads(r.read().decode("utf-8"))
            assert data["versionCode"] == vcode
            print("[成功] 服务器版本已生效: v%s (code=%d)" % (vname, vcode))
    except Exception as e:
        print("[警告] 链接验证失败(可能有CDN缓存，稍等片刻): %s" % e)

    client.close()
    print("完成！App下次打开时会自动提示更新。")


if __name__ == "__main__":
    main()
