#!/usr/bin/env python3
"""
LSPosed 模块开关（被 vmctl.sh 以 root 调用）。

直接改 LSPosed 自己的数据库 /data/adb/lspd/config/modules_config.db：
  query             打印 mod_enabled= / scope= / apk_path=
  enable <imePkg>   启用 VoiceMap 模块，作用域只留这一个输入法
  disable           停用模块（保留 scope 记录）

只在 android LSPosed 的 modules / scope 两张表上动手，别的模块一行不碰。
"""
import os
import sqlite3
import subprocess
import sys

DB = '/data/adb/lspd/config/modules_config.db'
MOD = 'com.pj.voicemap.ime'


def sh(*cmd):
    try:
        return subprocess.check_output(cmd, text=True, stderr=subprocess.DEVNULL).strip()
    except Exception:
        return ''


def apk_path():
    p = sh('pm', 'path', MOD)
    return p.split(':', 1)[1] if ':' in p else ''


def connect():
    if not os.path.exists(DB):
        print('ERR 本机没有 LSPosed（%s 不存在）：先装 LSPosed 再启用模块' % DB)
        sys.exit(2)
    c = sqlite3.connect(DB, timeout=20)
    c.execute('PRAGMA journal_mode=WAL')
    try:
        c.execute('select count(*) from modules').fetchone()
    except sqlite3.Error as e:
        print('ERR LSPosed 数据库结构不认识（%s）：到 LSPosed 里手动勾选本模块' % e)
        sys.exit(2)
    return c


def query():
    c = connect()
    row = c.execute('select mid, enabled, apk_path from modules where module_pkg_name=?',
                    (MOD,)).fetchone()
    if not row:
        print('mod_enabled=0')
        print('scope=')
        c.close()
        return
    mid, enabled, apk = row
    scopes = [r[0] for r in c.execute(
        'select app_pkg_name from scope where mid=?', (mid,))]
    print('mod_enabled=%d' % enabled)
    print('scope=%s' % ','.join(scopes))
    print('mod_apk=%s' % (apk or ''))
    c.close()


def columns(c, table):
    """按现有 schema 取列名 —— LSPosed 各版本的表结构不完全一样
    （v1.9.2 的 modules 就没有 auto_include，老的写死列名会直接 OperationalError）。"""
    return [r[1] for r in c.execute('PRAGMA table_info(%s)' % table)]


def enable(ime):
    apk = apk_path()
    if not apk:
        print('ERR 模块未安装：%s' % MOD)
        sys.exit(1)
    c = connect()
    mc = columns(c, 'modules')
    extra_auto = 'auto_include' in mc
    row = c.execute('select mid from modules where module_pkg_name=?', (MOD,)).fetchone()
    if row:
        mid = row[0]
        sql = 'update modules set apk_path=?, enabled=1' + (', auto_include=0' if extra_auto else '')
        c.execute(sql + ' where mid=?', (apk, mid))
    else:
        names = ['module_pkg_name', 'apk_path', 'enabled'] + (['auto_include'] if extra_auto else [])
        vals = [MOD, apk, 1] + ([0] if extra_auto else [])
        cur = c.execute('insert into modules(%s) values(%s)'
                        % (','.join(names), ','.join(['?'] * len(names))), vals)
        mid = cur.lastrowid
    c.execute('delete from scope where mid=?', (mid,))
    c.execute('insert or replace into scope(mid,app_pkg_name,user_id) values(?,?,0)', (mid, ime))
    c.commit()
    c.close()
    print('ok mid=%s enabled=1 scope=%s' % (mid, ime))


def disable():
    c = connect()
    row = c.execute('select mid from modules where module_pkg_name=?', (MOD,)).fetchone()
    if not row:
        print('ok 模块本来就没启用')
        c.close()
        return
    c.execute('update modules set enabled=0 where mid=?', (row[0],))
    c.commit()
    c.close()
    print('ok enabled=0')


def main():
    a = sys.argv[1:]
    if not a:
        a = ['query']
    if a[0] == 'query':
        query()
    elif a[0] == 'enable':
        if len(a) < 2 or not a[1]:
            print('ERR 缺少输入法包名')
            sys.exit(1)
        enable(a[1])
    elif a[0] == 'disable':
        disable()
    else:
        print('ERR 未知子命令：%s' % a[0])
        sys.exit(1)


if __name__ == '__main__':
    main()
