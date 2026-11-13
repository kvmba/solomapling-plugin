# 客户端数据参考：StringPool 与角色动作表

本目录记录 MapleStory **v83 GMS** 客户端（模块名 `` `Angel.exe ``，BeiDou 服配套构建）的静态逆向结果，
包含两套**运行期才会成形**的数据：

| 数据 | 数量 | 文档 | 数据文件 |
|---|---:|---|---|
| StringPool 加密字符串表 | 5658 | [stringpool.md](stringpool.md) | [data/stringpool.tsv](data/stringpool.tsv) |
| 角色动作表（raw action code → 动作名） | 162 | [actions.md](actions.md) | [data/actions.tsv](data/actions.tsv) |

## 目录结构

```text
docs/client/
├── README.md                 本文件：来源、注意事项、复现
├── stringpool.md             StringPool 说明 + 全量 5658 条
├── actions.md                角色动作表说明 + 全量 162 条
├── data/
│   ├── stringpool.tsv        index / enum name / text
│   └── actions.tsv           code / stringpool index / name
└── tools/
    ├── dump_stringpool.py    IDA 9.x 脚本：重新提取并解密字符串表
    └── dump_action_table.py  IDA 9.x 脚本：重新提取动作表
```

两份 `*.md` 是完整表格（可直接搜索），`data/*.tsv` 是同一份数据的机器可读版本；
`tools/*.py` 负责从 IDB 重新生成数据。

## 来源（Source）

> **脱敏约定**：本文档不记录采集机的盘符、用户名、桌面路径或任何绝对目录。
> 客户端与 IDB 的位置统一写作 `<client-dir>` / `<idb-dir>`；这些信息不影响本目录数据的可复现性。

| 项 | 值 |
|---|---|
| 模块名 | `` `Angel.exe ``（PE 模块名；前导反引号是名称的一部分） |
| 客户端 | MapleStory v83 GMS（BeiDou 服配套客户端） |
| ImageBase | `0x00400000` |
| 镜像大小 | `0x00A95000` |
| 首个段 | `0x00401000`（IDB 镜像未包含 PE 头，见注意事项 2） |
| IDB | `BeiDou.i64`（IDA Pro 9.4，Hex-Rays 就绪） |
| 分析工具 | IDA Pro 9.4 + `ida-pro-mcp` + 本目录 `tools/` 脚本 |
| 分析日期 | 2026-11-13 |
| 段布局 | 7 段；代码 `0x401000–0xAF0000`；字符串/数据 `0xAF03F8–0xBF9000`；`.mackt` `0xE92000` |

客户端位置：``<client-dir>/`Angel.exe``；IDB 位置：``<idb-dir>/BeiDou.i64``（占位符，非真实路径）。

## 两张表的关系

```text
动作表 code  --s_aCharacterActionData-->  动作名  --StringPool-->  明文
```

- 动作表的名字不是代码硬编码，而是启动时从 StringPool 解密后填入 `ACTIONDATA`；
- 因此动作表同时记录 `code`、`StringPool 索引`、`name` 三列，任一端都能反查；
- 字符串表里 `1008–1146` 这一段就是动作名块（139 条），动作表大量引用它。

## 通用注意事项

1. **都是运行期数据**。磁盘文件里，StringPool 是密文，动作表是 BSS（全 0）；
   客户端启动后才解密/填充。不要直接把磁盘字节当明文。
2. **IDB 镜像没有 PE 头**。首个段从 `0x401000` 开始，`0x400000` 处不是 `MZ`；
   `tools/` 只能作为 IDA 脚本运行（`idat -A -S...`），不能按文件偏移直接解析。
3. **StringPool 是懒解密 + 缓存**。`StringPool::GetString(i)` 第一次调用才解密第 i 条；
   本目录的表来自静态密文表，不是运行期缓存快照。
4. **编码**。绝大多数为 ASCII；34 条为韩文（CP949）。导出统一 UTF-8。
5. **转义**。文本中的 `\r`、`\n`、`\t`、`\\` 在 Markdown/TSV 中用转义写法（保证一行一条）；
   TSV 用 `\t` 分列，导出时不做 CSV 引号处理。
6. **地址只对这份构建成立**。换客户端版本/换服后必须按各文档的“复现/定位”重新扫描，
   不要硬编码地址。
7. **动作码 ≠ 移动动作值**。前者是 raw action（WZ 查找用），后者是移动动作（move action），
   经 `CAvatar::MoveAction2RawAction` 转换；详见 [actions.md](actions.md)。
8. **动作名大小写敏感**（`proneStab`、`assassinationS`、`swingT2PoleArm` 等），拼 WZ 路径不要改。
9. **版权**。字符串与动作名来自 Nexon/Wizet 的 v83 客户端；本目录仅作协议/资源名对照与互操作参考，
   不要再分发客户端本体或其资源文件。

## 快速使用

```bash
# 按动作码查名字
grep -P '^37\t' docs/client/data/actions.tsv

# 按名字查动作码
grep -i 'proneStab' docs/client/data/actions.tsv

# 全文搜索字符串（例如 GM 命令、URL）
grep -i 'supergm' docs/client/data/stringpool.tsv
grep -i 'http' docs/client/data/stringpool.tsv

# 按 StringPool 索引查
awk -F'\t' '$1==945' docs/client/data/stringpool.tsv
```

## 复现

```text
# 在 IDA 中加载 BeiDou.i64 后：
idat.exe -A -S"docs/client/tools/dump_stringpool.py" BeiDou.i64
idat.exe -A -S"docs/client/tools/dump_action_table.py" BeiDou.i64
```

两个脚本都会把结果写回 `docs/client/data/`：

- `stringpool.tsv` / `stringpool.json`
- `actions.tsv` / `actions.json`

定位锚点（本构建）：

| 目标 | 地址 | 签名/特征 |
|---|---|---|
| `StringPool::GetString`（实现） | `0x0079E993` | `B8 A2 70 AB 00` |
| StringPool 指针表 | `0x00BDC9D4` | `mov eax, off_BDC9D4[esi]` @ `0x79E9C9` |
| StringPool 主密钥 | `0x00B001EC` | `push offset dword_B001EC` @ `0x79EA19` |
| 动作表 | `0x00BEC620` | `s_aCharacterActionData`，162 × `0x18` |
| 动作表初始化 | `0x004A38E7` | `B8 D4 21 A8 00` |
| 动作名查找 | `0x004A8CE6` | `55 8B EC 51 8B 45 ? 83 65 ? ? 8D 04 40` |
| MoveAction→RawAction | `0x00451EC8` | `8B 44 24 ? 56 8B F1 8B 4C 24 ? 85 C9` |

更细的算法、结构、坑位见 [stringpool.md](stringpool.md) 与 [actions.md](actions.md)。
