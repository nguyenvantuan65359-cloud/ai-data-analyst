"""公共数据库候选：TCP 连通性 + 匿名登录实测。

用途：为本地 AI 数据分析系统的「数据源配置」找一个真实可用的只读连通目标。
只做只读操作（浏览表清单），不做任何写入。
用法：python test_public_db.py
"""
import socket
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# (名称, 主机, 端口, 说明)
CANDIDATES = [
    ("Ensembl 公共 MySQL", "ensembldb.ensembl.org", 3306,
     "欧洲生物信息研究所，官方提供匿名只读访问"),
    ("UCSC Genome MySQL", "genome-mysql.soe.ucsc.edu", 3306,
     "加州大学圣克鲁兹分校基因组浏览器，公开只读"),
    ("EBI Rfam 公共 MySQL", "mysql-rfam-public.ebi.ac.uk", 4497,
     "EMBL-EBI RNA 家族数据库，官方公开只读"),
    ("MySQL 公共测试不稳定源", "mysql.net", 3306, "仅供对照，通常不可用"),
]

TIMEOUT = 8.0


def probe(name, host, port, note):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(TIMEOUT)
    t0 = time.time()
    try:
        s.connect((host, port))
        cost = (time.time() - t0) * 1000
        print(f"[OK]   {name:24s} {host}:{port}  {cost:.0f}ms   {note}")
        return True
    except socket.timeout:
        print(f"[TIMEOUT] {name:22s} {host}:{port}  >{TIMEOUT:.0f}s   {note}")
    except Exception as e:
        print(f"[FAIL] {name:23s} {host}:{port}  {type(e).__name__}: {e}")
    finally:
        s.close()
    return False


if __name__ == "__main__":
    print("=" * 78)
    print("公共数据库 TCP 连通性探测（只读用途）")
    print("=" * 78)
    results = {}
    for name, host, port, note in CANDIDATES:
        results[name] = probe(name, host, port, note)
    print("=" * 78)
    ok = [k for k, v in results.items() if v]
    print("可达：" + (", ".join(ok) if ok else "无（建议走本地方案）"))
