# StringPool 全量字符串表

> 客户端内置加密字符串：5658 条。数据文件：`data/stringpool.tsv`；总体来源与免责说明见 [README](README.md)。

## 1. 它是什么

MapleStory v83 客户端把 UI 文本、提示、URL、WZ 路径、字体名、EULA 等内置字符串
加密后放在数据段，运行时由 `StringPool::GetString(idx)` **按需解密并缓存**。
索引就是枚举 `StringPoolStrings` 的值（IDA 里显示为 `SP_<n>_<UPPER_SNAKE>`，`n == 索引`）。

## 2. 数据结构

| 项 | 值 |
|---|---|
| 指针表 | `off_BDC9D4` @ `0x00BDC9D4`，`char*[5658]` |
| 主密钥 | `0x00B001EC`，16 字节：`D6 DE 75 86 46 64 A3 71 E8 E6 7B D3 33 30 E7 2E` |
| 密钥长度 | `0x00B001FC` = `0x10` |
| 单条布局 | `+0x00`：单条 key 字节；`+0x01` 起：NUL 结尾的密文 |
| 存放顺序 | 逆序：index 0 @ `0x00B37234`，index 5657 @ `0x00B00204` |
| 最长条目 | index 3041，1962 字节明文 |

## 3. 算法

解码一条字符串（与 `sub_79ECDE` / `sub_79EBF3` 等价）：

```python
def rot16(key_table, key):
    t = bytearray(key_table)          # 16 字节主密钥的副本
    if key >= 8:                      # 1) 字节轮转
        n = (key >> 3) % len(t)
        t = t[n:] + t[:n]
    bits = key & 7                    # 2) 128-bit 大端循环左移
    if bits:
        v = int.from_bytes(t, "big")
        v = ((v << bits) | (v >> (8 * len(t) - bits))) & ((1 << (8 * len(t))) - 1)
        t = bytearray(v.to_bytes(len(t), "big"))
    return t

def decrypt(entry, key_table):
    """entry = 指针表指向的原始内存：key 字节 + 密文 C 字符串"""
    end = entry.find(b"\x00", 1)     # 注意从 1 开始，key 字节本身可以是 0x00
    key, cipher = entry[0], entry[1:end]
    t = rot16(key_table, key)
    out = bytearray()
    for i, c in enumerate(cipher):
        k = t[i % len(t)]
        p = c ^ k
        out.append(k if p == 0 else p)  # 解码器永不输出 NUL
    return bytes(out)
```

要点：

- 单条 key 字节同时决定**字节轮转量** `(key >> 3) % 16` 和**位轮转量** `key & 7`。
- 异或流就是轮转后的 16 字节密钥表，按 `i % 16` 循环。
- 若 `c ^ k == 0`，输出保留 `k`，因此明文永远不会出现 NUL。

## 4. 入口 / 调用链

| 地址 | 符号 | 作用 |
|---|---|---|
| `0x00406455` | `StringPool::GetString(unsigned)` | 公开包装，调用私有实现 |
| `0x0079E993` | `StringPool::GetString(idx, flag)` | 真实实现；查缓存，未命中才解密 |
| `0x0079ECDE` | `sub_79ECDE` | 单条解密：取 key、轮转密钥、逐字节异或 |
| `0x0079E780` | `sub_79E780` | 复制 16 字节密钥表并调用轮转 |
| `0x0079EBF3` | `sub_79EBF3` | 字节轮转 + 128-bit 位轮转 |
| `0x0079E7E8` | `sub_79E7E8` | 取 `key[i % 16]` |

运行时缓存：`StringPool` 单例内有一个 `ZXString*` 数组，`GetString` 命中即直接返回；
所以静态表（本文件）与运行期缓存（解密后副本）是两回事。

## 5. 统计

| 指标 | 值 |
|---|---:|
| 条目数 | 5658 |
| 去重后 | 5658（0 条重复） |
| 总字符数 | 205,116 |
| 最长 | 1962 字符（index 3041） |
| 非 ASCII | 34 条（韩文，CP949） |
| 长度 1-15 | 2088 |
| 长度 16-31 | 1219 |
| 长度 32-63 | 1789 |
| 长度 64-255 | 531 |
| 长度 256+ | 31 |

内容分布（按枚举段）：0–1007 主要是 UI/提示/字体/URL；**1008–1146 是角色动作名块**
（139 条，被[动作表](actions.md)直接引用）；1147–3000 为 WZ 路径与道具/技能文本；
3029–3045 为 EULA 长文本；5000+ 为补充词条（heal/jump/dead 等）。

## 6. 注意事项

1. **总数是 5658，不是 5659**。`0xBE223C` 处的值 `0xB3726C` 不属于指针表——它后面紧跟的是另一组代码指针，
   只是恰好落进字符串区；枚举 `StringPoolStrings` 的最后一个真实成员也是 `SP_5657_UNDEAD`。
2. **找 NUL 必须从 `+1` 开始**。单条 key 字节可以是 `0x00`（本构建有 22 条），从 0 开始 `find(b"\x00")`
   会把整条截成空串。
3. **不要用指针差推长度**。条目按逆序（高地址 → 低地址）存放，相邻索引的指针差不是字符串长度；
   长度只能由密文自身的 NUL 决定（最长 1962 字节，读取缓冲建议 ≥ 0x8000）。
4. **索引 == 枚举值**，可用 IDA 的 `StringPoolStrings` 做交叉校验（如 945 → `SP_945_WVSCLIENTMTX` → `WvsClientMtx`）。
5. **编码**：绝大多数是 ASCII；34 条韩文（CP949）。导出统一转 UTF-8；无法解码时回退 CP1252（本构建未触发）。
6. **转义**：文本中的 `\r` `\n` `\t` `\\` 在表格/TSV 里是转义写法，保证一行一条；`|` 在 Markdown 中转义为 `\|`。
7. **密钥表是静态的**：`0xB001EC` 只被 `GetString` 引用，运行期不会被改写；直接读文件/镜像即可。
8. **懒解密**：没被 UI 用到的条目也不会影响本表——本表来自静态密文表，不是运行期缓存快照。
9. 地址仅对这份构建成立；换客户端后按 [README](README.md#复现) 重新定位。

## 7. 复现

```text
idat.exe -A -S"docs/client/tools/dump_stringpool.py" BeiDou.i64
```

脚本会重新读取指针表与密钥、解密全部条目，并把结果写回 `docs/client/data/`：
`stringpool.tsv`（可读表格）与 `stringpool.json`（含 VA/key/密文 hex）。
定位锚点：`StringPool::GetString` 实现签名 `B8 A2 70 AB 00`；指针表由 `mov eax, off_BDC9D4[esi]`
（`0x79E9C9`）引用；密钥表由 `push offset dword_B001EC`（`0x79EA19`）引用。

## 附录：全量 5658 条

> `#` 为索引（= 枚举值），`枚举名` 来自 IDA `StringPoolStrings`，`文本` 为解密结果。

| # | 枚举名 | 文本 |
|---:|---|---|
| 0 | SP_0_HTTP__PASSPORTNEXONNET_PART_REGISTRATION_AGECHECK | http://passport.nexon.net/?PART=/Registration/AgeCheck |
| 1 | SP_1_HTTP__PASSPORTNEXONNET_WZASPXPART_ACCOUNTS_FORGOTID | http://passport.nexon.net/WZ.ASPX?PART=/Accounts/ForgotId |
| 2 | SP_2_HTTP__PASSPORTNEXONNET_WZASPXPART_ACCOUNTS_FORGOTPASSWORD | http://passport.nexon.net/WZ.ASPX?PART=/Accounts/ForgotPassword |
| 3 | SP_3_HTTP__MAPLESTORYNEXONNET_WZASPXPART_DOWNLOADS_GAMEPATCHES | http://maplestory.nexon.net/WZ.ASPX?PART=/Downloads/GamePatches |
| 4 | SP_4_HTTP__MAPLESTORYNEXONNET_PART_NEWS_NOTICEBBSIDX3255 | http://maplestory.nexon.net/?PART=/News/Notice&bbsidx=3255 |
| 5 | SP_5_HTTP__BILLINGNEXONNET_WZASPXPART_PURCHASING_ACCOUNT | http://billing.nexon.net/WZ.ASPX?PART=/Purchasing/Account |
| 6 | SP_6_HTTP__NXCACHENEXONNET_MAPLESTORY_ENDING_ENDINGMSHTML | http://nxcache.nexon.net/maplestory/ending/ending_ms.html |
| 7 | SP_7_HTTP__PASSPORTNEXONNET_PART_MYMAPLE_VERIFYCODE | http://passport.nexon.net/?PART=/MyMaple/Verifycode |
| 8 | SP_8_TAHOMA | Tahoma |
| 9 | SP_9_ARIAL_NARROW | Arial Narrow |
| 10 | SP_10_EQP | Eqp |
| 11 | SP_11_SETUP | Setup |
| 12 | SP_12_BEGINNER | Beginner |
| 13 | SP_13_WARRIOR | Warrior |
| 14 | SP_14_BOWMAN | Bowman |
| 15 | SP_15_THIEF | Thief |
| 16 | SP_16_PIRATE | Pirate |
| 17 | SP_17_ | 매니저 |
| 18 | SP_18_CYGNUS_KNIGHTS | Cygnus Knights |
| 19 | SP_19_AVAILABLE_TO | Available to |
| 20 | SP_20_SUPERGM | SuperGM |
| 21 | SP_21_MWLB | MWLB |
| 22 | SP_22_SWORDMAN | Swordman |
| 23 | SP_23_FIGHTER | Fighter |
| 24 | SP_24_CRUSADER | Crusader |
| 25 | SP_25_HERO | Hero |
| 26 | SP_26_PAGE | Page |
| 27 | SP_27_WHITE_KNIGHT | White Knight |
| 28 | SP_28_PALADIN | Paladin |
| 29 | SP_29_SPEARMAN | Spearman |
| 30 | SP_30_DRAGON_KNIGHT | Dragon Knight |
| 31 | SP_31_DARK_KNIGHT | Dark Knight |
| 32 | SP_32_WIZARD | Wizard |
| 33 | SP_33_MAGE | Mage |
| 34 | SP_34_ARCH_MAGE | Arch Mage |
| 35 | SP_35_WIZARD_FIREPOISON | Wizard (Fire,Poison) |
| 36 | SP_36_MAGEFIRE_POISON | Mage(Fire, Poison) |
| 37 | SP_37_ARCH_MAGEFIREPOISON | Arch Mage(Fire,Poison) |
| 38 | SP_38_WIZARDICELIGHTNING | Wizard(Ice,Lightning) |
| 39 | SP_39_MAGEICELIGHTNING | Mage(Ice,Lightning) |
| 40 | SP_40_ARCH_MAGEICELIGHTNING | Arch Mage(Ice,Lightning) |
| 41 | SP_41_CLERIC | Cleric |
| 42 | SP_42_PRIEST | Priest |
| 43 | SP_43_BISHOP | Bishop |
| 44 | SP_44_ARCHER | Archer |
| 45 | SP_45_HUNTER | Hunter |
| 46 | SP_46_RANGER | Ranger |
| 47 | SP_47_BOWMASTER | Bowmaster |
| 48 | SP_48_CROSSBOW_MAN | Crossbow man |
| 49 | SP_49_SNIPER | Sniper |
| 50 | SP_50_MARKSMAN | Marksman |
| 51 | SP_51_ROGUE | Rogue |
| 52 | SP_52_ASSASSIN | Assassin |
| 53 | SP_53_HERMIT | Hermit |
| 54 | SP_54_NIGHT_LORD | Night Lord |
| 55 | SP_55_BANDIT | Bandit |
| 56 | SP_56_CHIEF_BANDIT | Chief Bandit |
| 57 | SP_57_SHADOWER | Shadower |
| 58 | SP_58_BRAWLER | Brawler |
| 59 | SP_59_MARAUDER | Marauder |
| 60 | SP_60_BUCCANEER | Buccaneer |
| 61 | SP_61_GUNSLINGER | Gunslinger |
| 62 | SP_62_OUTLAW | Outlaw |
| 63 | SP_63_CORSAIR | Corsair |
| 64 | SP_64_NOBLESSE | Noblesse |
| 65 | SP_65_THERE_ARE_TOO_MANY_REQUESTS_ON_THE_SERVER_AT_THIS_POINT_PLEASE_TRY_AGAIN_LATER | There are too many requests on the server at this point. Please try again later. |
| 66 | SP_66_YOU_CANT_DO_IT_ONCE_YOURE_DEAD | You can't do it once you're dead. |
| 67 | SP_67_WILL_YOU_GO_BACK_TO_THE_MAIN_MENU | Will you go back to the main menu? |
| 68 | SP_68_PRESS_CHECK_BUTTON_FIRST_R_NAND_SEE_IF_THE_NAME_IS_AVAILABLE | Press check button first\r\nand see if the name is available. |
| 69 | SP_69_YOUR_SSN_DOESNT_MATCH_R_NPLEASE_TRY_AGAIN | Your SSN doesn't match.\r\nPlease try again. |
| 70 | SP_70_YOU_CANT_CREATE_A_CHARACTER_ANYMORE | You can't create a character anymore. |
| 71 | SP_71_ARE_YOU_SURE_YOU_WANT_TO_DELETE_THE_CHARACTER | Are you sure you want to delete the character? |
| 72 | SP_72__WARNING__R_NIF_THE_NAME_CONTAINS_A_FOUL_LANGUAGE_OR_ANYTHING_SIMILAR_R_NYOUR_AC | [Warning]\r\nIf the name contains a foul language or anything similar,\r\nyour account may be subject to restrictions. |
| 73 | SP_73_THE_NAME_S_IS_TAKEN | The name '%s' is taken. |
| 74 | SP_74_THE_NAME_S_IS_AVAILABLE | The name '%s' is available. |
| 75 | SP_75_THE_NAMECHECK_DIDNT_WORK__R_NDUE_TO_AN_UNKNOWN_ERROR | The namecheck didn't work \r\ndue to an unknown error. |
| 76 | SP_76_THE_CHARACTER_WASNT_MADE__R_NDUE_TO_AN_UNKNOWN_ERROR | The character wasn't made \r\ndue to an unknown error. |
| 77 | SP_77_THE_CHANNEL_YOU_SELECTED_IS_CURRENTLY_UNAVAILABLE_R_N_PLEASE_SELECT_ANOTHER_CHAN | The channel you selected is currently unavailable.\r\n Please select another channel or try again later. |
| 78 | SP_78_THE_CONNECTION_HAS_BEEN_LOST_PLEASE_TRY_AGAIN_LATER__R_NIF_THIS_ERROR_OCCURS_AGA | The connection has been lost. Please try again later. \r\nIf this error occurs again, please visit (GameGuard website) for more information. |
| 79 | SP_79_THERE_WAS_AN_ERROR_TRYING_TO_CONNECT_TO_THE_GAME_SERVER_R_NPLEASE_TRY_AGAIN_LATE | There was an error trying to connect to the game server.\r\nPlease try again later. |
| 80 | SP_80_WE_ARE_UNABLE_TO_CONNECT_TO_THE_LOGIN_SERVER_DUE_TO_A_SERVER_CHECK_A_FIREWALL_BL | We are unable to connect to the login server due to a server check, a firewall block or other network issues. \r\n Please check the MapleStory homepage for ongoing server checks as well as your firewall settings. |
| 81 | SP_81_CHECK_OUT_THE_MAPLESTORY_NOTICE_BOARD | Check out the MapleStory Notice board. |
| 82 | SP_82_YOU_HAVE_BEEN_DISCONNECTED_FROM_THE_LOGIN_SERVER__R_NPLEASE_TRY_AGAIN_LATER | You have been disconnected from the login server. \r\nPlease try again later. |
| 83 | SP_83_THERE_IS_CURRENTLY_NOT_ENOUGH_AVAILABLE_MEMORY_TO_RUN_MAPLESTORY_R_NPLEASE_VISIT | There is currently not enough available memory to run MapleStory.\r\nPlease visit the FAQ section at maplestory.nexon.net for further details. |
| 84 | SP_84_WE_WERE_UNABLE_TO_LOCATE_A_MAPLESTORY_DATA_FILE_ON_YOUR_COMPUTER_R_NPLEASE_DOWNL | We were unable to locate a MapleStory data file on your computer.\r\nPlease download the latest client from maplestory.nexon.net. |
| 85 | SP_85_THE_CLIENT_IS_OUTDATED_PLEASE_DOWNLOAD_THE_LATEST_CLIENT_FROM_MAPLESTORYNEXONNET | The client is outdated. Please download the latest client from maplestory.nexon.net. |
| 86 | SP_86_YOU_APPEAR_TO_BE_MISSING_A_FEW_FILES_NEEDED_TO_RUN_THE_GAME_R_NPLEASE_VISIT_MAPL | You appear to be missing a few files needed to run the game.\r\nPlease visit maplestory.nexon.net and download the latest version of the client. |
| 87 | SP_87_THE_CLIENT_IS_OUTDATED__R_NPLEASE_DOWNLOAD_THE_LATEST_CLIENT__R_NFROM_MAPLESTORY | The client is outdated. \r\nPlease download the latest client \r\nfrom maplestory.nexon.net and try again. |
| 88 | SP_88_AN_ERRORR_OCCURED_WHILE_COMMUNICATING_WITH_THE_SERVER_R_NPLEASE_TRY_AGAIN_LATER | An errorr occured while communicating with the server.\r\nPlease try again later. |
| 89 | SP_89_THERE_WAS_AN_ERROR_WITH_ANOTHER_PROGRAM_SHARING_THE_SAME_RESOURCES_R_NCLOSE_OTHE | There was an error with another program sharing the same resources.\r\nClose other programs before restarting the game. If the problem persists, then either restart the system or check the customer service section on our website. |
| 90 | SP_90_THERE_WAS_AN_ERROR_WITH_ANOTHER_PROGRAM_SHARING_THE_SAME_RESOURCES_R_NTHE_PROBLE | There was an error with another program sharing the same resources.\r\nThe problem may have occured due to a computer virus. Please use the vaccine to run through a virus check. |
| 91 | SP_91_EITHER_THE_IP_ADDRESS_IS_UNAVAILABLE_TO_CONNECT_OR__R_NYOU_HAVE_USED_UP_YOUR_AVA | Either the IP address is unavailable to connect or \r\nyou have used up your available game time for the day. |
| 92 | SP_92______R_N____ | 메이플스토리 홈페이지에서 로그인 한 후\r\n게임 시작 버튼을 눌러 주세요. |
| 93 | SP_93_PLEASE_DOWNLOAD_THE_FULL_CLIENT_OF_MAPLESTORY | Please download the full client of maplestory. |
| 94 | SP_94_MAPLESTORY_ERROR__ACTIONFRAME_LOADMAPPPER_FAILED | MapleStory Error : Actionframe loadmappper failed. |
| 95 | SP_95_MAPLESTORY_ERROR__LOADING_EMPLOYEE_FAILED | MapleStory Error : Loading employee failed. |
| 96 | SP_96_MAPLESTORY_ERROR__LOADING_MOB_FAILED | MapleStory Error : Loading mob failed. |
| 97 | SP_97_MAPLESTORY_ERROR__LOADING_MORPH_FAILED | MapleStory Error : Loading morph failed. |
| 98 | SP_98_MAPLESTORY_ERROR__LOADING_NPC_FAILED | MapleStory Error : Loading NPC failed. |
| 99 | SP_99_MAPLESTORY_ERROR__LOADING_PET_FAILED | MapleStory Error : Loading pet failed. |
| 100 | SP_100_MAPLESTORY_ERROR__LOADING_REACTOR_FAILED | MapleStory Error : Loading reactor failed. |
| 101 | SP_101_MAPLESTORY_ERROR__LOADING_TAMINGMOB_FAILED | MapleStory Error : Loading tamingmob failed. |
| 102 | SP_102_MAPLESTORY_ERROR__SPRITE_COMMONSLOT_FAILED | MapleStory Error : Sprite commonslot failed. |
| 103 | SP_103_MAPLESTORY_ERROR__SPRITE_INIT_FAILEDEMPTY | MapleStory Error : Sprite init failed(empty). |
| 104 | SP_104_MAPLESTORY_ERROR__SPRITE_INIT_FAILEDUNKNOWN | MapleStory Error : Sprite init failed(unknown). |
| 105 | SP_105_MAPLESTORY_ERROR__MAPLETV_INIT_FAILED | MapleStory Error : MapleTV init failed. |
| 106 | SP_106_MAPLESTORY_ERROR__QUESTMAN_INIT_FAILED | MapleStory Error : QuestMan init failed. |
| 107 | SP_107_MAPLESTORY_ERROR__ITEMINFO_ERROR | MapleStory Error : iteminfo error. |
| 108 | SP_108_MAPLESTORY_ERROR__SKILLINFO_ERROR | MapleStory Error : skillinfo error. |
| 109 | SP_109_MAPLESTORY_ERROR__LOADING_MOBSKILL_FAILED | MapleStory Error : Loading mobskill failed. |
| 110 | SP_110_MAPLESTORY_ERROR__LOADING_COMMODITY_FAILED | MapleStory Error : Loading commodity failed. |
| 111 | SP_111_MAPLESTORY_ERROR__LOADING_PACKAGE_FAILED | MapleStory Error : Loading package failed. |
| 112 | SP_112_MAPLESTORY_ERROR__CASHSHOP_FAILEDTYPE1 | MapleStory Error : Cashshop failed(type1). |
| 113 | SP_113_MAPLESTORY_ERROR__CASHSHOP_FAILEDTYPE2 | MapleStory Error : Cashshop failed(type2). |
| 114 | SP_114_MAPLESTORY_ERROR__DRAGGABLEITEM_ERROR | MapleStory Error : DraggableItem error. |
| 115 | SP_115_MAPLESTORY_ERROR__EMPLOYEE_ERROR | MapleStory Error : Employee error. |
| 116 | SP_116_MAPLESTORY_ERROR__MOB_ERROR | MapleStory Error : Mob error. |
| 117 | SP_117_MAPLESTORY_ERROR__NPC_ERROR | MapleStory Error : NPC error. |
| 118 | SP_118_MAPLESTORY_ERROR__PET_ERROR | MapleStory Error : Pet error. |
| 119 | SP_119_MAPLESTORY_ERROR__SUMMONED_ERROR | MapleStory Error : Summoned error. |
| 120 | SP_120_MAPLESTORY_ERROR__ACTIONMAN_ERRORTYPE1 | MapleStory Error : ActionMan error(type1). |
| 121 | SP_121_MAPLESTORY_ERROR__ACTIONMAN_ERRORTYPE2 | MapleStory Error : ActionMan error(type2). |
| 122 | SP_122__WHISPER | /whisper |
| 123 | SP_123__PARTY | /party |
| 124 | SP_124__BUDDY | /buddy |
| 125 | SP_125__GUILD | /guild |
| 126 | SP_126__ALLIANCE | /alliance |
| 127 | SP_127__COUPLE | /couple |
| 128 | SP_128__ALL | /all |
| 129 | SP_129__FIND | /find |
| 130 | SP_130__TRADE | /trade |
| 131 | SP_131__SHOP | /shop |
| 132 | SP_132__MOVEITEM | /moveitem |
| 133 | SP_133__PARTYINFO | /partyinfo |
| 134 | SP_134__MAKEPARTY | /makeparty |
| 135 | SP_135__LEAVEPARTY | /leaveparty |
| 136 | SP_136__PARTYINVITE | /partyinvite |
| 137 | SP_137__PARTYEXPEL | /partyexpel |
| 138 | SP_138__PARTYSEARCH | /partysearch |
| 139 | SP_139__GUILDINFO | /guildinfo |
| 140 | SP_140__LEAVEGUILD | /leaveguild |
| 141 | SP_141__GUILDINVITE | /guildinvite |
| 142 | SP_142__GUILDEXPEL | /guildexpel |
| 143 | SP_143__VOMIT | /vomit |
| 144 | SP_144__OOPS | /oops |
| 145 | SP_145__INVITE | /invite |
| 146 | SP_146__PET | /pet |
| 147 | SP_147_PLEASE_TRY_AGAIN | Please try again. |
| 148 | SP_148_NOT_AVAILABLE_WITH_AN_ADMIN_ACCOUNT | Not available with an Admin account. |
| 149 | SP_149_NOT_AVAILABLE_WITH_AN_MWLB_ACCOUNT | Not available with an MWLB account. |
| 150 | SP_150_NOT_AVAILABLE | Not available |
| 151 | SP_151_YOU_HAVE_WHISPERED_TO_S | You have whispered to '%s' |
| 152 | SP_152_UNABLE_TO_FIND_S | Unable to find '%s' |
| 153 | SP_153_S_IS_AT_S | '%s' is at '%s'. |
| 154 | SP_154_S_IS_AT_THE_CASH_SHOP | '%s' is at the Cash Shop. |
| 155 | SP_155_S_IS_CURRENTLY_AT_S | '%s' is currently at '%s'. |
| 156 | SP_156_S_IS_AT_THE_EVENT_MAP | '%s' is at the event map. |
| 157 | SP_157_S_HAVE_CURRENTLY_DISABLED_WHISPERS | '%s' have currently disabled whispers. |
| 158 | SP_158_EITHER_THE_PARTY_DOESNT_EXIST_OR_NO_MEMBER_OF_YOUR_PARTY_IS_LOGGED_ON | Either the party doesn't exist or no member of your party is logged on. |
| 159 | SP_159_YOU_ARE_NOT_MARRIED_OR_YOUR_SPOUSE_IS_CURRENTLY_OFFLINE | You are not married or your spouse is currently offline. |
| 160 | SP_160_YOU_HAVE_NO_BUDDY_AVAILABLE_FOR_CHAT | You have no buddy available for chat. |
| 161 | SP_161_YOU_ARE_NOT_IN_A_GUILD_YET_OR_NONE_OF_YOUR_GUILD_MEMBER_IS_ONLINE | You are not in a guild yet or none of your guild member is online. |
| 162 | SP_162_YOU_HAVE_SUCCESSFULLY_BLOCKED_ACCESS | You have successfully blocked access. |
| 163 | SP_163_THE_BLOCKING_FAILED | The blocking failed. |
| 164 | SP_164_THE_UNBLOCKING_HAS_BEEN_SUCCESSFUL | The unblocking has been successful |
| 165 | SP_165_THE_COMMAND_WAS_TYPED_WRONG_PLEASE_DOUBLE_CHECK | The command was typed wrong. Please double-check. |
| 166 | SP_166_THE_UNBLOCKING_WAS_UNSUCCESSFUL | The unblocking was unsuccessful. |
| 167 | SP_167_YOU_HAVE_SUCCESSFULLY_REMOVED_THE_NAME_FROM_THE_RANKS | You have successfully removed the name from the ranks. |
| 168 | SP_168_YOU_HAVE_ENTERED_AN_INVALID_CHARACTER_NAME | You have entered an invalid character name. |
| 169 | SP_169_YOU_HAVE_FAILED_TO_REMOVE_YOURSELF_FROM_THE_RANKS | You have failed to remove yourself from the ranks. |
| 170 | SP_170_YOU_HAVE_EITHER_ENTERED_A_WRONG_NPC_NAME_OR | You have either entered a wrong NPC name or |
| 171 | SP_171_YOUR_REQUEST_FAILED | Your request failed. |
| 172 | SP_172_RENAMING_SUCCESSFUL | Renaming Successful. |
| 173 | SP_173_RENAMING_FAILED___CANT_FIND_THE_HIRED_MERCHANT | Renaming Failed - Can't find the Hired Merchant. |
| 174 | SP_174_HIRED_MERCHANT_LOCATED_AT__S | Hired Merchant located at : %s |
| 175 | SP_175_UNABLE_TO_FIND_THE_HIRED_MERCHANT | Unable to find the hired merchant. |
| 176 | SP_176__RESETMAP | /resetmap |
| 177 | SP_177__QUIZ | /quiz |
| 178 | SP_178__ANSWER | /answer |
| 179 | SP_179__CHECK | /check |
| 180 | SP_180__EXPEL | /expel |
| 181 | SP_181__EXPLAIN | /explain |
| 182 | SP_182__START | /start |
| 183 | SP_183__RESET | /reset |
| 184 | SP_184__DIVIDETEAM | /divideteam |
| 185 | SP_185__OMOK | /omok |
| 186 | SP_186__MEMORYGAME | /memorygame |
| 187 | SP_187__PRIZE | /prize |
| 188 | SP_188__SENDUSER | /senduser |
| 189 | SP_189__GIVEPRIZE | /giveprize |
| 190 | SP_190__MATCHTABLE | /matchtable |
| 191 | SP_191__U | /u |
| 192 | SP_192__SEND | /send |
| 193 | SP_193__LITHHARBOR | /lithharbor |
| 194 | SP_194__LUDIBRIUM | /ludibrium |
| 195 | SP_195__ORBIS | /orbis |
| 196 | SP_196__SOUTHPERRY | /southperry |
| 197 | SP_197__OLA10 | /ola10 |
| 198 | SP_198__OLA11 | /ola11 |
| 199 | SP_199__OLA12 | /ola12 |
| 200 | SP_200__OLA20 | /ola20 |
| 201 | SP_201__OLA21 | /ola21 |
| 202 | SP_202__OLA22 | /ola22 |
| 203 | SP_203__OLA30 | /ola30 |
| 204 | SP_204__OLA31 | /ola31 |
| 205 | SP_205__OLA32 | /ola32 |
| 206 | SP_206__OLA40 | /ola40 |
| 207 | SP_207__OLA41 | /ola41 |
| 208 | SP_208__OLA42 | /ola42 |
| 209 | SP_209__OLA50 | /ola50 |
| 210 | SP_210__OLA51 | /ola51 |
| 211 | SP_211__OLA52 | /ola52 |
| 212 | SP_212__FITNESS1 | /fitness1 |
| 213 | SP_213__FITNESS2 | /fitness2 |
| 214 | SP_214__FITNESS3 | /fitness3 |
| 215 | SP_215__FITNESS4 | /fitness4 |
| 216 | SP_216__OX | /ox |
| 217 | SP_217__COCONUT1 | /coconut1 |
| 218 | SP_218__COCONUT2 | /coconut2 |
| 219 | SP_219__COCONUT3 | /coconut3 |
| 220 | SP_220__COKESEASON1 | /cokeseason1 |
| 221 | SP_221__COKESEASON2 | /cokeseason2 |
| 222 | SP_222__COKESEASON3 | /cokeseason3 |
| 223 | SP_223__LEVEL1 | /level1 |
| 224 | SP_224__LEVEL2 | /level2 |
| 225 | SP_225__LEVEL3 | /level3 |
| 226 | SP_226__LEVEL4 | /level4 |
| 227 | SP_227__LEVEL5 | /level5 |
| 228 | SP_228__LEVEL6 | /level6 |
| 229 | SP_229__LEVEL7 | /level7 |
| 230 | SP_230__LEVEL8 | /level8 |
| 231 | SP_231__PACKAGE1 | /package1 |
| 232 | SP_232__PACKAGE2 | /package2 |
| 233 | SP_233__POINTER00 | /Pointer00 |
| 234 | SP_234__POINTER01 | /Pointer01 |
| 235 | SP_235__POINTER02 | /Pointer02 |
| 236 | SP_236__POINTER03 | /Pointer03 |
| 237 | SP_237__POINTER04 | /Pointer04 |
| 238 | SP_238__NOTICEHE | /noticeHe |
| 239 | SP_239__NOTICEEL | /noticeEl |
| 240 | SP_240__NOTICEPE | /noticePe |
| 241 | SP_241__NOTICEKC | /noticeKC |
| 242 | SP_242__NOTICELH | /noticeLH |
| 243 | SP_243__NOTICEOR | /noticeOr |
| 244 | SP_244__NOTICELU | /noticeLu |
| 245 | SP_245__NOTICEFO | /noticeFo |
| 246 | SP_246__CRISP | /crisp |
| 247 | SP_247_ATTENTION_THIS_IS_ATHENA_PIERCE_A_HORDE_OF_MONSTERS_IS_HEADED_FOR_HENESYS_ALL_BR | ATTENTION! This is Athena Pierce! A horde of monsters is headed for Henesys! All brave warriors are ordered to Henesys immediately! Let the battle begin! |
| 248 | SP_248_ATTENTION_THIS_IS_GRENDEL_THE_REALLY_OLD_A_HORDE_OF_MONSTERS_IS_HEADED_FOR_ELLIN | ATTENTION! This is Grendel the Really Old! A horde of monsters is headed for Ellinia! All brave warriors are ordered to Ellinia immediately! We shall prevail! |
| 249 | SP_249_ATTENTION_THIS_IS_DANCES_WITH_BALROG_A_HORDE_OF_MONSTERS_IS_HEADED_FOR_PERION_AL | ATTENTION! This is Dances with Balrog! A horde of monsters is headed for Perion! All brave warriors are ordered to Perion immediately! May your sword be as strong as your will! |
| 250 | SP_250_ATTENTION_THIS_IS_THE_DARK_LORD_A_HORDE_OF_MONSTERS_IS_HEADED_FOR_KERNING_CITY_A | ATTENTION! This is the Dark Lord! A horde of monsters is headed for Kerning City! All brave warriors are ordered to Kerning City immediately! Let us teach our enemies the folly of their actions! |
| 251 | SP_251_ATTENTION_THIS_IS_VIKIN_A_HORDE_OF_MONSTERS_IS_HEADED_FOR_LITH_HARBOR_ALL_BRAVE_ | ATTENTION! This is Vikin! A horde of monsters is headed for Lith Harbor! All brave warriors are ordered to Lith Harbor immediately! It's battle time! |
| 252 | SP_252_ATTENTION_THIS_IS_ERICSON_A_HORDE_OF_MONSTERS_IS_HEADED_FOR_ORBIS_ALL_BRAVE_WARR | ATTENTION! This is Ericson! A horde of monsters is headed for Orbis! All brave warriors are ordered to Orbis immediately! Fight hard, fight well! |
| 253 | SP_253_ATTENTION_THIS_IS_MARCEL_A_HORDE_OF_MONSTERS_IS_HEADED_FOR_LUDIBRIUM_ALL_BRAVE_W | ATTENTION! This is Marcel! A horde of monsters is headed for Ludibrium! All brave warriors are ordered to Ludibrium immediately! Show these punks we don't toy around! |
| 254 | SP_254_ATTENTION_THIS_IS_A_DISTRESS_CALL_A_HORDE_OF_MONSTERS_IS_HEADED_FOR_FOLK_TOWN_AL | ATTENTION! This is a distress call! A horde of monsters is headed for Folk Town! All brave warriors are ordered to Folk Town immediately! May the spirit of the Maple Tree guide your weapon! |
| 255 | SP_255__STARTNOTICE | /startnotice |
| 256 | SP_256__ENDNOTICE | /endnotice |
| 257 | SP_257_THE_MONSTERS_HAVE_BEGUN_A_RUTHLESS_ASSAULT_STAND_YOUR_GROUND_HOLD_THE_LINE_AND_P | The monsters have begun a ruthless assault! Stand your ground, hold the line and protect the town! |
| 258 | SP_258_EXCELLENT_WORK_THE_TOWN_HAS_WITHSTOOD_THE_INVASION_OF_THE_MONSTERS_UNSCATHED_THA | Excellent work! The town has withstood the invasion of the monsters unscathed, thanks to your superior battle skills. If you're the person that collected "The Force of Darkness," please head to the Maple Administrator located near you. We thank you again! |
| 259 | SP_259__OPEN | /open |
| 260 | SP_260__ALERTM | /alertm |
| 261 | SP_261__NOTICEM | /noticem |
| 262 | SP_262__MAPLE | /maple |
| 263 | SP_263__STORY | /story |
| 264 | SP_264__FINDHM | /findhm |
| 265 | SP_265__END | \t/end |
| 266 | SP_266_YOU_CANT_DO_IT_RIGHT_NOW_R_NCATCH_YOUR_BREATH_AND_THEN_TRY_AGAIN | You can't do it right now.\r\nCatch your breath and then try again. |
| 267 | SP_267_YOU_ARE_CURRENTLY_AT_A_PLACE_WHERE_R_NTHE_WORLD_MAP_IS_NOT_AVAILABLE | You are currently at a place where\r\nthe world map is not available. |
| 268 | SP_268_THE_WORLD_MAP_IS_UNAVAILABLE_IN__R_NTHE_BEGINNERS_VERSION_R_NPLEASE_DOWNLOAD_THE | The World Map is unavailable in \r\nthe Beginner's Version.\r\nPlease download the full client at\r\nmaplestory.nexon.net for access to this feature. |
| 269 | SP_269_THE_WORLD_MAP_IS_UNAVAILABLE_TO_GUEST_ID_PLAYERS_R_NPLEASE_DOWNLOAD_THE_FULL_CLI | The World Map is unavailable to Guest ID players.\r\nPlease download the full client at\r\nmaplestory.nexon.net \r\nfor access to this feature. |
| 270 | SP_270_THIS_NPC_IS_LOCATED_AT_A_SPOT_NOT_R_NAVAILABLE_THROUGH_THE_WORLD_MAP | This NPC is located at a spot not\r\navailable through the World Map. |
| 271 | SP_271_ENTER_YOUR_COMMENTS_THROUGH_THE_MEGAPHONE | Enter your comments through the megaphone. |
| 272 | SP_272_CURSING_IS_NOT_ALLOWED | Cursing is not allowed. |
| 273 | SP_273_USE_THE_MEGAPHONE_TO_TEXT_UP_TO_60_CHARACTERS | Use the megaphone to text up to 60 characters. |
| 274 | SP_274_THE_EFFECT_IS_TAKING_PLACE_RIGHT_NOW_R_NPLEASE_TRY_IT_LATER | The effect is taking place right now.\r\nPlease try it later. |
| 275 | SP_275_YOU_CANT_USE_IT_AT_MAPLE_ISLAND | You can't use it at Maple Island. |
| 276 | SP_276_YOU_CANT_USE_IT_HERE_IN_THIS_MAP | You can't use it here in this map. |
| 277 | SP_277_YOU_CANT_DO_IT_HERE_IN_THIS_MAP | You can't do it here in this map. |
| 278 | SP_278_YOU_CANT_MOVE_TO_ANOTHER_ISLAND | You can't move to another island. |
| 279 | SP_279_TOO_MUCH_INTEREST_CAN_HINDER_YOUR_PROCESS_OF_LEVELING_UP_R_N_R_NWILL_YOU_REALLY_ | Too much interest can hinder your process of leveling up.\r\n\r\nwill you really summon the monster? |
| 280 | SP_280_YOU_CANT_CHAT_RIGHT_NOW | You can't chat right now. |
| 281 | SP_281_YOU_HAVE_GAINED_EXPERIENCE_D | You have gained experience (+%d) |
| 282 | SP_282_INTERNET_CAFE_EXP_BONUS_D | Internet Cafe EXP Bonus (+%d) |
| 283 | SP_283_BONUS_EVENT_EXP_D | Bonus Event EXP (+%d) |
| 284 | SP_284_BONUS_WEDDING_EXP_D | Bonus Wedding EXP (+%d) |
| 285 | SP_285_EQUIP_ITEM_BONUS_EXP_D | Equip Item Bonus EXP (+%d) |
| 286 | SP_286_EARNED_SPIRIT_WEEK_EVENT_BONUS_EXP_D | Earned 'Spirit Week Event' bonus EXP. (+%d) |
| 287 | SP_287_THE_NEXT_D_COMPLETED_QUESTS_WILL_INCLUDE_ADDITIONAL_EVENT_BONUS_EXP | The next %d completed quests will include additional Event Bonus EXP. |
| 288 | SP_288_YOU_HAVE_GAINED_FAME_D | You have gained fame. (+%d) |
| 289 | SP_289_YOU_HAVE_LOST_FAME_D | You have lost fame (%d) |
| 290 | SP_290_YOU_HAVE_GAINED_MESOS_D | You have gained mesos (+%d) |
| 291 | SP_291_INTERNET_CAFE_MESO_BONUS_D | Internet Cafe Meso Bonus (+%d) |
| 292 | SP_292_YOU_HAVE_LOST_MESOS_D | You have lost mesos. (%d) |
| 293 | SP_293_YOU_HAVE_LOST_ITEMS_S_D | You have lost items (%s %d) |
| 294 | SP_294_YOU_HAVE_LOST_AN_ITEM_S | You have lost an item (%s) |
| 295 | SP_295_YOU_CANT_GET_ANYMORE_ITEMS | You can't get anymore items. |
| 296 | SP_296__S__HAS_PASSED_ITS_EXPIRATION_DATE_AND_WILL_BE_REMOVED_FROM_YOUR_INVENTORY | [%s] has passed its expiration date and will be removed from your inventory. |
| 297 | SP_297_OTHER_REQUESTS_ARE_IN_PROCESS_R_NPLEASE_TRY_AGAIN_IN_A_LITTLE_BIT | Other requests are in process.\r\nPlease try again in a little bit. |
| 298 | SP_298_YOU_HAVE_RAISED_SS_LEVEL_OF_FAME | You have raised '%s''s level of fame. |
| 299 | SP_299_YOU_HAVE_DROPPED_SS_LEVEL_OF_FAME | You have dropped '%s''s level of fame. |
| 300 | SP_300_THE_USER_NAME_IS_INCORRECTLY_ENTERED | The user name is incorrectly entered. |
| 301 | SP_301_USERS_UNDER_LEVEL_15_ARE_UNABLE_TO_TOGGLE_WITH_FAME | Users under level 15 are unable to toggle with fame. |
| 302 | SP_302_YOU_CANT_RAISE_OR_DROP_A_LEVEL_OF_FAME_ANYMORE_FOR_TODAY | You can't raise or drop a level of fame anymore for today. |
| 303 | SP_303_YOU_CANT_RAISE_OR_DROP_A_LEVEL_OF_FAME_OF_THAT_CHARACTER_ANYMORE_FOR_THIS_MONTH | You can't raise or drop a level of fame of that character anymore for this month. |
| 304 | SP_304_S_HAVE_RAISED_SS_LEVEL_OF_FAME | '%s' have raised '%s''s level of fame. |
| 305 | SP_305_S_HAVE_DROPPED_SS_LEVEL_OF_FAME | '%s' have dropped '%s''s level of fame. |
| 306 | SP_306_THE_LEVEL_OF_FAME_HAS_NEITHER_BEEN_RAISED_OR_DROPPED_DUE_TO_AN_UNEXPECTED_ERROR | The level of fame has neither been raised or dropped due to an unexpected error. |
| 307 | SP_307_PLEASE_TRY_IT_AGAIN_LATER | Please try it again later. |
| 308 | SP_308_S_IS_CURRENTLY_BLOCKING_ANY_PARTY_INVITATIONS | %s is currently blocking any party invitations. |
| 309 | SP_309_S_HAVE_DENIED_REQUEST_TO_THE_PARTY | %s have denied request to the party. |
| 310 | SP_310_YOU_HAVE_CREATED_A_NEW_PARTY | You have created a new party. |
| 311 | SP_311_YOU_HAVE_BEEN_EXPELLED_FROM_THE_PARTY | You have been expelled from the party. |
| 312 | SP_312_YOU_HAVE_LEFT_THE_PARTY | You have left the party. |
| 313 | SP_313_S_HAVE_BEEN_EXPELLED_FROM_THE_PARTY | '%s' have been expelled from the party. |
| 314 | SP_314_S_HAVE_LEFT_THE_PARTY | '%s' have left the party. |
| 315 | SP_315_YOU_HAVE_QUIT_AS_THE_LEADER_OF_THE_PARTY_THE_PARTY_HAS_BEEN_DISBANDED | You have quit as the leader of the party. The party has been disbanded. |
| 316 | SP_316_YOU_HAVE_LEFT_THE_PARTY_SINCE_THE_PARTY_LEADER_QUIT | You have left the party since the party leader quit. |
| 317 | SP_317_YOU_HAVE_JOINED_THE_PARTY | You have joined the party. |
| 318 | SP_318_S_HAS_JOINED_THE_PARTY | '%s' has joined the party. |
| 319 | SP_319_ALREADY_HAVE_JOINED_A_PARTY | Already have joined a party. |
| 320 | SP_320_A_BEGINNER_CANT_CREATE_A_PARTY | A beginner can't create a party. |
| 321 | SP_321_YOU_HAVE_YET_TO_JOIN_A_PARTY | You have yet to join a party. |
| 322 | SP_322_THE_PARTY_YOURE_TRYING_TO_JOIN_IS_ALREADY_IN_FULL_CAPACITY | The party you're trying to join is already in full capacity. |
| 323 | SP_323_YOU_ARE_NOT_THE_MASTER_OF_THE_PARTY | You are not the master of the party. |
| 324 | SP_324_YOU_MAY_NOT_INVITE_YOURSELF_TO_THE_PARTY | \tYou may not invite yourself to the party. |
| 325 | SP_325_SIS_NOT_A_MEMBER_OF_YOUR_PARTY | '%s'is not a member of your party. |
| 326 | SP_326_AS_A_GM_YOURE_FORBIDDEN_FROM_CREATING_A_PARTY | As a GM, you're forbidden from creating a party. |
| 327 | SP_327_AS_A_GM_YOURE_FORBIDDEN_FROM_JOINING_A_PARTY | As a GM, you're forbidden from joining a party. |
| 328 | SP_328_IT_IS_IMPOSSIBLE_TO_INVITE_SOMEONE_TO_THE_PARTY | It is impossible to invite someone to the party. |
| 329 | SP_329_YOUR_REQUEST_FOR_A_PARTY_DIDNT_WORK_DUE_TO_AN_UNEXPECTED_ERROR | Your request for a party didn't work due to an unexpected error. |
| 330 | SP_330_UNABLE_TO_FIND_THE_REQUESTED_CHARACTER_IN_THIS_CHANNEL | Unable to find the requested character in this channel. |
| 331 | SP_331_PARTY_MEMBER | Party member: |
| 332 | SP_332_YOURE_THE_LEADER_OF_THE_PARTY | You're the leader of the party. |
| 333 | SP_333_SINCE_THE_TIME_HAS_EXCEEDED_DENIAL_MESSAGE_HAS_BEEN_SENT_TO_THE_PARTY_LEADER | Since the time has exceeded, denial message has been sent to the party leader. |
| 334 | SP_334_S_IS_CURRENTLY_NOT_ACCEPTING_GUILD_INVITE_MESSAGE | %s is currently not accepting guild invite message. |
| 335 | SP_335_S_HAS_DENIED_YOUR_GUILD_INVITATION | %s has denied your guild invitation. |
| 336 | SP_336_S_YOU_HAVE_JOINED_A_GUILD | %s You have joined a guild. |
| 337 | SP_337_NEW_GUILD_HAS_BEEN_MADE | New guild has been made. |
| 338 | SP_338_YOU_HAVE_QUIT_THE_GUILD | You have quit the guild. |
| 339 | SP_339_S_HAS_BEEN_EXPELLED_FROM_THE_GUILD | '%s' has been expelled from the guild. |
| 340 | SP_340_DELETED_CHRACTERS_HAVE_BEEN_EXPELLED | Deleted Chracters have been expelled. |
| 341 | SP_341_S_QUITTED_THE_GUILD | '%s' quitted the guild. |
| 342 | SP_342_EXPEL_S_FROM_THE_GUILD | Expel '%s' from the guild? |
| 343 | SP_343_WOULD_YOU_LIKE_TO_QUIT_THE_GUILD | Would you like to quit the guild? |
| 344 | SP_344_SQUITTED_THE_GUILD | '%s'quitted the guild. |
| 345 | SP_345_THE_GUILD_HAS_BEEN_DISBANDED | The guild has been disbanded. |
| 346 | SP_346_YOU_HAVE_ENTERED_THE_GUILD | You have entered the guild. |
| 347 | SP_347_S_HAS_JOINED_THE_GUILD | '%s' has joined the guild. |
| 348 | SP_348_ALREADY_JOINED_THE_GUILD | Already joined the guild. |
| 349 | SP_349_YOU_CANNOT_MAKE_A_GUILD_DUE_TO_THE_LIMITATION_OF_MINIMUM_LEVEL_REQUIREMENT | You cannot make a guild, due to the limitation of minimum level requirement. |
| 350 | SP_350_YOU_ARE_NOT_IN_THE_GUILD | You are not in the guild. |
| 351 | SP_351_YOU_CANNOT_QUIT_THE_GUILD_SINCE_YOU_ARE_THE_MASTER_OF_THE_GUILD | You cannot quit the guild, since you are the master of the guild. |
| 352 | SP_352_THE_GUILD_YOU_ARE_TRYING_TO_JOIN_HAS_ALREADY_REACHED_THE_MAX_NUMBER_OF_USERS | The guild, you are trying to join, has already reached the max number of users. |
| 353 | SP_353_BEGINNERS_CANNOT_BE_THE_MASTER | Beginners cannot be the master. |
| 354 | SP_354_YOU_ARE_NOT_THE_MASTER_OF_THE_GUILD | You are not the master of the guild. |
| 355 | SP_355_YOU_CANNOT_INVITE_YOURSELF_TO_THE_GUILD | You cannot invite yourself to the guild. |
| 356 | SP_356_S_IS_NOT_THE_MEMBER_OF_THE_GUILD | '%s' is not the member of the guild. |
| 357 | SP_357_ONLY_GUILD_MASTER_CAN_EXPEL_JRMASTERS | Only Guild Master can expel Jr.Masters. |
| 358 | SP_358_YOU_MAY_NOT_BAN_THE_GUILD_MASTER | You may not ban the Guild Master. |
| 359 | SP_359_ADMIN_CANNOT_MAKE_A_GUILD | Admin cannot make a guild. |
| 360 | SP_360_YOU_CANNOT_INVITE_MORE_PEOPLE_TO_THE_GUILD | You cannot invite more people to the guild. |
| 361 | SP_361_THE_GUILD_REQUEST_HAS_NOT_BEEN_ACCEPTED_DUE_TO_UNKNOWN_REASON | The guild request has not been accepted, due to unknown reason. |
| 362 | SP_362_THE_CHARACTER_CANNOT_BE_FOUND_IN_THE_CURRENT_CHANNEL | The character cannot be found in the current channel. |
| 363 | SP_363_GUILD_MEMBER | Guild Member: |
| 364 | SP_364_YOU_ARE_THE_GUILD_MASTER | You are the guild master. |
| 365 | SP_365_THE_MAXIMUM_NUMBER_OF_THE_GUILD_MEMBERS_HAS_BEEN_INCREASED_TO_D | The maximum number of the guild members has been increased to %d. |
| 366 | SP_366_UNABLE_TO_FIND_THE_CHARACTER | Unable to find the character. |
| 367 | SP_367_THE_BUDDY_IS_ALREADY_REGISTERED | The buddy is already registered. |
| 368 | SP_368_YOU_CANT_ENTER_YOURSELF_AS_YOUR_BUDDY | You can't enter yourself as your buddy. |
| 369 | SP_369_THE_USER_IS_NOT_REGISTERED | The user is not registered. |
| 370 | SP_370_YOU_HAVE_NOT_REQUESTED_BUDDIES_WITH_THAT_USER | You have not requested buddies with that user. |
| 371 | SP_371_THE_PORTAL_IS_CLOSED_FOR_NOW | The portal is closed for now. |
| 372 | SP_372_YOU_CANT_MOVE_TO_A_DIFFERENT_ISLAND | You can't move to a different island. |
| 373 | SP_373_INCORRECT_CHANNEL_NUMBER | Incorrect channel number. |
| 374 | SP_374_YOURE_AT_THE_PREMIUM_SERVICE_INTERNET_CAFE_R_NWHILE_YOURE_LOGGED_ON_YOULL_RECEIV | You're at the premium service internet cafe.\r\nWhile you're logged on, you'll receive various premium services. |
| 375 | SP_375__R_NWINDOWS_98_USERS_MAY_HAVE_TROUBLE_PLAYING_THIS_GAME_R_NWE_RECOMMEND_USERS_TO | \r\nWindows 98 users may have trouble playing this game.\r\nWe recommend users to use Windows XP or 2000. |
| 376 | SP_376_OHYOURE_THE_ONE_THATLL_TAKE_CARE_OF_MY_BABY_CAN_YOU_HEAR_ME_I_AM_A_WIZARD_WHO_IS | Oh...you're the one that'll take care of my baby! Can you hear me? I am a wizard who is at the very bottom of the World Tree, extracting the Water of Life from there. I'm speaking to your mind right now.\r\n\r\nThat kid...is a magical animal that I've poured life into by pouring the Water of Life at the doll that I've made myself. For the longest time, I've been studying and researching on life itself, and learned to actually create a spell that creates life. But it's only possible with the Water of Life...and since it comes out so little, I can only give that kid a life for so long... |
| 377 | SP_377_WHEN_THE_TIME_PASSES_AND_THE_WATER_OF_LIFE_THATS_BEEN_SPRINKLED_ONTO_IT_DRIES_UP | When the time passes and the Water of Life that's been sprinkled onto it dries up, it'll turn back into a doll. Yes, it's very unfortunate, but not to worry. If you pour the Water of Life again on it, it'll come back to life.\r\n\r\n.And for that, I collect the Water of Life everyday here deep in the underground. I sent my apprentice up overground, so there comes a time where you'll need the Water of Life, please go visit him. Don't forget that the Water of Life will last once poured onto the doll...Please take care of it... |
| 378 | SP_378_THE_TIME_HAS_RUN_OUT_SO_IT_CANT_MOVE | The time has run out, so it can't move. |
| 379 | SP_379_YOU_CANNOT_REVIVE_THIS_PET_R_NYOU_CAN_PURCHASE_A_NEW_PET_R_NIN_THE_CASH_SHOP_WOU | You cannot revive this pet.\r\nYou can purchase a new pet\r\nin the Cash Shop. Would you like\r\nto move to the Cash Shop? |
| 380 | SP_380_THE_CLOSENESS_INCREASED_D | The closeness increased (+%d) |
| 381 | SP_381_THE_CLOSENESS_DECREASED_D | The closeness decreased (%d) |
| 382 | SP_382_THE_PET_WENT_BACK_HOME_BECAUSE_ITS_HUNGRY | The pet went back home because it's hungry. |
| 383 | SP_383_THE_PETS_MAGICAL_TIME_HAS_RUN_OUT_AND_SO_IT_HAS_TURNED_BACK_INTO_A_DOLL | The pet's magical time has run out, and so it has turned back into a doll. |
| 384 | SP_384_YOU_HAVE_EXCEEDED_THE_MAXIMUM_NUMBER_R_N_OF_PETS_THAT_CAN_ACCOMPANY_YOU_R_NPLEAS | You have exceeded the maximum number\r\n of pets that can accompany you.\r\nPlease try again after \r\nyou change the living pet back into a doll. |
| 385 | SP_385_WOULD_YOU_LIKE_TO_CHANGE_YOUR_BOSS_PET_R_N_THE_ITEM_IGNORE_SKILL_AS_WELL_AS__R_N | Would you like to change your Boss Pet?\r\n (The "Item Ignore" skill, as well as \r\nthe stats for speed and jump will \r\nfollow that of the Lead Pet) |
| 386 | SP_386_PLEASE_SELECT_THE_PET_YOU_WOULD_LIKE_TO_PUT_THE_PET_EQUIPMENT_ON | Please select the pet you would like to put the pet equipment on. |
| 387 | SP_387_THE_SCROLL_LIGHTS_UP_AND_THEN_ITS_MYSTERIOUS_POWER_HAS_BEEN_TRANSFERRED_TO_THE_I | The scroll lights up, and then its mysterious power has been transferred to the item. |
| 388 | SP_388_THE_SCROLL_LIGHTS_UP_BUT_THE_ITEM_WINDS_UP_AS_IF_NOTHING_HAPPENED | The scroll lights up, but the item winds up as if nothing happened |
| 389 | SP_389_THE_ITEM_IS_DESTROYED_DUE_TO_THE_OVERWHELMING_POWER_OF_THE_SCROLL | The item is destroyed due to the overwhelming power of the scroll. |
| 390 | SP_390_WILL_YOU_PLAY_S_ON_THE_JUKEBOX | Will you play <%s> on the jukebox? |
| 391 | SP_391_THE_CHARACTER_YOU_LOGGED_ON_WITH_R_NHAS_NOT_REQUESTED_A_NAME_CHANGE | The character you logged on with\r\nhas not requested a name change. |
| 392 | SP_392_THE_SCHEDULE_FOR_THE_TRIP_IS_AS_FOLLOWS | The schedule for the trip is as follows. |
| 393 | SP_393_YOU_CAN_START_ENTERING_THE_GATE_AT_5_MIN_TIL_TAKE_OFF | You can start entering the gate at 5 min. til take off. |
| 394 | SP_394_THE_ROOM_IS_ALREADY_CLOSED | The room is already closed. |
| 395 | SP_395_YOU_CANT_ENTER_THE_ROOM_DUE_TO_FULL_CAPACITY | You can't enter the room due to full capacity. |
| 396 | SP_396_OTHER_REQUESTS_ARE_BEING_FULFILLED_THIS_MINUTE | Other requests are being fulfilled this minute. |
| 397 | SP_397_YOU_CANT_DO_IT_WHILE_YOURE_DEAD | You can't do it while you're dead. |
| 398 | SP_398_YOU_CANT_DO_IT_IN_THE_MIDDLE_OF_THE_EVENT | You can't do it in the middle of the event. |
| 399 | SP_399_THIS_CHARACTER_IS_UNABLE_TO_DO_IT | This character is unable to do it. |
| 400 | SP_400_YOU_ARE_NOT_ALLOWED_TO_TRADE_OTHER_ITEMS_AT_THIS_POINT | You are not allowed to trade other items at this point. |
| 401 | SP_401_YOU_CANT_ESTABLISH_A_MINIROOM_RIGHT_HERE | You can't establish a miniroom right here. |
| 402 | SP_402_YOU_CAN_ONLY_MAKE_TRADE_R_NIF_YOU_TWO_ARE_IN_THE_SAME_MAP | You can only make trade\r\nif you two are in the same map. |
| 403 | SP_403_S_IS_DOING_SOMETHING_ELSE_RIGHT_NOW | '%s' is doing something else right now. |
| 404 | SP_404_S_HAVE_DENIED_INVITATION | '%s' have denied invitation. |
| 405 | SP_405_S_IS_CURRENTLY_NOT_ACCEPTING_ANY_INVITATION | '%s' is currently not accepting any invitation. |
| 406 | SP_406_TRADE_CANCELLED_R_NBY_THE_OTHER_CHARACTER | Trade cancelled.\r\nby the other character. |
| 407 | SP_407_TRADE_SUCCESSFUL_R_NPLEASE_CHECK_THE_RESULTS | Trade successful.\r\nPlease check the results. |
| 408 | SP_408_TRADE_SUCCESSFUL_R_NRECEIVED_D_MESOS_AFTER_FEES_R_NPLEASE_CHECK_THE_RESULTS | Trade successful.\r\nReceived %d mesos after fees.\r\nPlease check the results. |
| 409 | SP_409_TRADE_UNSUCCESSFUL | Trade unsuccessful. |
| 410 | SP_410_YOU_CANNOT_MAKE_THE_TRADE_BECAUSE_R_NTHERE_ARE_SOME_ITEMS_WHICH_R_NYOU_CANNOT_CA | You cannot make the trade because\r\nthere are some items which\r\nyou cannot carry more than one. |
| 411 | SP_411_YOU_CANNOT_MAKE_THE_TRADE_BECAUSE_R_NTHE_OTHER_PERSONS_ON_A_DIFFERENT_MAP | You cannot make the trade because\r\nthe other person's on a different map. |
| 412 | SP_412_HOW_MUCH_MONEY_WILL_YOU_PUT_UP | How much money will you put up? |
| 413 | SP_413_ARE_YOU_SURE_YOU_WANT_TO_TRADE | Are you sure you want to trade? |
| 414 | SP_414_PLEASE_ENTER_THE_NAME_OF_THE_STORE_R_N_R_NIF_YOU_MOVE_BEFORE_YOU_OPEN_THE_STORE_ | Please enter the name of the store.\r\n\r\nIf you move before you open the store,\r\n the store may not be opened. |
| 415 | SP_415_D_FOR_S_MESOS | %d for %s mesos. |
| 416 | SP_416_S_MESOS | %s mesos. |
| 417 | SP_417_THE_SHOP_IS_CLOSED | The shop is closed. |
| 418 | SP_418_THE_ITEMS_ARE_OUT_OF_STOCK | The items are out of stock. |
| 419 | SP_419_THE_NUMBER_OF_ITEMS_SHOULD_BE_BASED_R_NON_THE_NUMBER_OF_ITEMS_IN_A_BUNDLE | The number of items should be based\r\non the number of items in a bundle. |
| 420 | SP_420_THE_TOTAL_PRICE_IS_D_MESOS_R_NWILL_YOU_MAKE_THE_PURCHASE | The total price is %d mesos.\r\nWill you make the purchase? |
| 421 | SP_421_YOU_CANT_SELL_IT_ANYMORE | You can't sell it anymore. |
| 422 | SP_422_SELL_BY_THE_BUNDLE | Sell by the bundle. |
| 423 | SP_423_PRICE_PER_BUNDLE | Price per bundle |
| 424 | SP_424_PRICE_PER_UNIT | Price per unit. |
| 425 | SP_425_NUMBER_OF_ITEMS_TO_SELL | Number of items to sell. |
| 426 | SP_426_NUMBER_OF_ITEMS_PER_BUNDLE | Number of items per bundle. |
| 427 | SP_427_THE_ITEM_HAS_BEEN_SOLD_ALREADY | The item has been sold already. |
| 428 | SP_428_YOU_MAY_NOT_ENTER_THIS_STORE | You may not enter this store. |
| 429 | SP_429_WOULD_YOU_LIKE_TO_BAN_S | Would you like to ban %s? |
| 430 | SP_430_THE_USER_HAS_BEEN_BANNED | The user has been banned. |
| 431 | SP_431_YOU_HAVE_BEEN_BANNED_FROM_THE_ROOM_FOR_EXCEEDING_THE_TIME_LIMIT_YOU_ARE_NOW_BLOC | You have been banned from the room for exceeding the time limit. You are now blocked from re-entering this store. |
| 432 | SP_432_PLEASE_ENTER_THE_NAME_OF_THE_GAME_YOULL_MAKE | Please enter the name of the game you'll make. |
| 433 | SP_433_YOU_DONT_HAVE_ENOUGH_MESOS_TO_ENTER | You don't have enough mesos to enter. |
| 434 | SP_434_YOU_CANT_START_THE_GAME_HERE | You can't start the game here. |
| 435 | SP_435_THE_PERSONAL_STORE_CAN_ONLY_BE_BUILT_R_NAT_THE_FREE_MARKET_AVAILABLE_AT_MAIN_TOW | The personal store can only be built\r\nat the free market available at main towns. |
| 436 | SP_436_YOU_MAY_ONLY_USE_IT_AT_ROOM_722_LOCATED_AT_THE_2ND_FLOOR_AND_ABOVE_OF_THE_FREE_M | You may only use it at Room 7~22, located at the 2nd floor and above of the Free Market Entrance. |
| 437 | SP_437__S__HAVE_ENTERED | [%s] have entered. |
| 438 | SP_438__S__HAVE_LEFT | [%s] have left. |
| 439 | SP_439__S__HAVE_CALLED_TO_LEAVE_AFTER_THIS_GAME | [%s] have called to leave after this game. |
| 440 | SP_440__S__HAVE_CANCELLED_THE_REQUEST_TO_LEAVE_AFTER_THIS_GAME | [%s] have cancelled the request to leave after this game. |
| 441 | SP_441__S__HAVE_BEEN_EXPELLED | [%s] have been expelled. |
| 442 | SP_442_YOU_HAVE_BEEN_EXPELLED | You have been expelled. |
| 443 | SP_443__S__HAVE_FORFEITED | [%s] have forfeited. |
| 444 | SP_444__S__HAVE_REQUESTED_A_HANDICAP | [%s] have requested a handicap. |
| 445 | SP_445_YOU_HAVE_LEFT_THE_ROOM | You have left the room. |
| 446 | SP_446__S_S_TURN | [%s]'s turn. |
| 447 | SP_447__S__HAS_MATCHED_CARDS_PLEASE_CONTINUE | [%s] has matched cards. Please continue. |
| 448 | SP_448__S__CANT_START_THE_GAME_DUE_TO_LACK_OF_MESOS | [%s] can't start the game due to lack of mesos. |
| 449 | SP_449_THE_GAME_HAS_STARTED | The game has started. |
| 450 | SP_450_THE_GAME_HAS_ENDED_R_NTHE_ROOM_WILL_AUTOMATICALLY_CLOSE_IN_10_SEC | The game has ended.\r\nThe room will automatically close in 10 sec. |
| 451 | SP_451_10_SEC_LEFT | 10 sec. left. |
| 452 | SP_452_THE_ROOM_IS_CLOSED | The room is closed. |
| 453 | SP_453_YOU_WIN | You win. |
| 454 | SP_454_ITS_A_TIE | It's a tie. |
| 455 | SP_455_YOU_LOST | You lost. |
| 456 | SP_456_ARE_YOU_SURE_YOU_WANT_TO_GIVE_UP | Are you sure you want to give up? |
| 457 | SP_457_WILL_YOU_EXPEL_THE_USER | Will you expel the user? |
| 458 | SP_458_YOUR_OPPONENT_REQUESTS_A_TIE_R_NWILL_YOU_ACCEPT_IT | Your opponent requests a tie.\r\nWill you accept it? |
| 459 | SP_459_WILL_YOU_REQUEST_A_TIE | Will you request a tie? |
| 460 | SP_460_YOUR_OPPONENT_DENIED_YOUR_REQUEST_FOR_A_TIE | Your opponent denied your request for a tie. |
| 461 | SP_461_YOU_CAN_ONLY_REQUEST_A_HANDICAP_ONCE_PER_GAME | You can only request a handicap once per game. |
| 462 | SP_462_YOUR_OPPENTENT_HAS_REQUESTED_TO__R_NWITHDRAW_HIS_HER_LAST_MOVE_R_NDO_YOU_ACCEPT | Your oppentent has requested to \r\nwithdraw his/her last move.\r\nDo you accept? |
| 463 | SP_463_REQUEST_TO_WITHDRAW_YOUR_LAST_MOVE | Request to withdraw your last move? |
| 464 | SP_464_YOUR_OPPONENT_DENIED_YOUR_REQUEST | Your opponent denied your request. |
| 465 | SP_465_WILL_YOU_CALL_TO_LEAVE_AFTER_THIS_GAME | Will you call to leave after this game? |
| 466 | SP_466_WILL_YOU_CANCEL_THE_REQUEST_R_NTO_LEAVE_AFTER_THIS_GAME | Will you cancel the request\r\nto leave after this game? |
| 467 | SP_467_TIME_LEFT__D_SEC | Time left : %d sec. |
| 468 | SP_468_ITS___S__S_TURN | It's [ %s ]'s turn. |
| 469 | SP_469_ARE_YOU_SURE_YOU_WANT_TO_LEAVE | Are you sure you want to leave? |
| 470 | SP_470_PLEASE_ENTER_THE_PASSWORD | Please enter the password. |
| 471 | SP_471_DO_YOU_WANT_TO_PLAY_IN_PRIVATE | Do you want to play in private? |
| 472 | SP_472_YOU_ARE_UNABLE_TO_ENTER_THE_TOURNAMENT_ROOM | You are unable to enter the tournament room. |
| 473 | SP_473_YOU_HAVE_DOUBLE_3S | \tYou have double-3's. |
| 474 | SP_474_YOU_CANT_PUT_IT_THERE | You can't put it there. |
| 475 | SP_475_YOU_CANT_FLY_IT_THERE | You can't fly it there. |
| 476 | SP_476_THE_RECEIVERS_CHARACTER_NAME_IS_INCORRECT | The receiver's character name is incorrect. |
| 477 | SP_477_THE_MOVE_IS_SUCCESSFUL_PLEASE_CHECK_THE_RESULT | The move is successful. Please check the result. |
| 478 | SP_478_IM_BUSY_RIGHT_NOW | I'm busy right now. |
| 479 | SP_479_YOU_CANT_TRANSFER_CHARACTERS_IN_THE_SAME_ACCOUNT | You can't transfer characters in the same account. |
| 480 | SP_480_THE_USER_IS_LOGGING_IN | The user is logging in. |
| 481 | SP_481_THE_USER_DOESNT_HAVE_ENOUGH_SPACE_ON_THE_INVENTORY | The user doesn't have enough space on the inventory. |
| 482 | SP_482_THERES_NO_MONEY_OR_ITEM_TO_TRANSFER | There's no money or item to transfer. |
| 483 | SP_483_THE_CHARACTER_IS_NOT_REGISTERED | The character is not registered. |
| 484 | SP_484_THE_REQUEST_IS_DENIED_FOR_AN_UNKNOWN_REASON | The request is denied for an unknown reason. |
| 485 | SP_485_ONCE_YOU_DELETE_THIS_CASH_ITEM_THE_ITEM_WILL_R_NDISAPPEAR_AND_D_OF_THE_PURCHASED | Once you delete this cash item, the item will\r\ndisappear, and %d%% of the purchased price, %d,\r\n will be accumulated towards your MaplePoints.\r\nAccumulated MaplePoints will be\r\nused to purchase other cash items.\r\n.Are you sure you want to delete this item?\r\n |
| 486 | SP_486_THE_SELECTED_ITEMS_WILL_BE_DELETED_AND__R_NMAPLEPOINTS_WILL_NOT_BE_ACCUMULATED_R | The selected items will be deleted, and \r\nMaplePoints will not be accumulated.\r\nAre you sure you want to delete this item?\r\n |
| 487 | SP_487_S_ADDING_INVENTORY_SLOTS_D_NX_R_NCONFIRM_INVENTORY_SLOT_PURCHASE | %s adding inventory slots. (%d NX)\r\nConfirm inventory slot purchase? |
| 488 | SP_488_ADDING_SLOTS_TO_THE_STORAGE_D_NX_R_NARE_YOU_SURE_YOU_WANT_TO_ADD_SLOTS_R_NTO_YOU | Adding slots to the storage (%d NX)\r\nAre you sure you want to add slots\r\nto your storage? |
| 489 | SP_489_ADD_A_CHARACTER_SLOT_D_NX_R_NARE_YOU_SURE_YOU_WANT_TO_ADD_ANOTHER_R_NCHARACTER_S | Add a character slot (%d NX)\r\nAre you sure you want to add another\r\ncharacter slot? |
| 490 | SP_490_YOU_MAY_TEST_THIS_ITEM_FOR_FREE_R_NTHE_FREE_ITEM_WILL_ONLY_LAST_FOR_1_DAY_AND_R_ | You may test this item for free.\r\nThe free item will only last for 1 day and\r\nyou will only be able to try each once.\r\nPress OK to try this item for free or\r\npress CANCEL to purchase the item\r\nfor 90 days. |
| 491 | SP_491_THE_REQUESTED_NUMBER_OF_SLOTS_R_NCANT_BE_ADDED | The requested number of slots\r\ncan't be added. |
| 492 | SP_492_THE_STORAGE_SLOTS_CANT_BE_EXTENDED | The storage slots can't be extended. |
| 493 | SP_493_YOU_MAY_NOT_ADD_ANY_MORE_CHARACTER_SLOTS | You may not add any more character slots. |
| 494 | SP_494_YOUR_JOB_LIMIT_DOES_NOT_ALLOW_R_NYOU_TO_USE_THIS_ITEM__R_NARE_YOU_SURE_YOU_WANT_ | Your job limit does not allow\r\nyou to use this item. \r\nAre you sure you want to buy the item? |
| 495 | SP_495_YOUR_LEVEL_LIMIT_DOES_NOT_ALLOW_R_NYOU_TO_USE_THIS_ITEM_R_NARE_YOU_SURE_YOU_WANT | Your level limit does not allow\r\nyou to use this item.\r\nAre you sure you want to buy the item? |
| 496 | SP_496_YOUR_FAME_LIMIT_DOES_NOT_ALLOW_R_NYOU_TO_USE_THIS_ITEM_R_NARE_YOU_SURE_YOU_WANT_ | Your fame limit does not allow\r\nyou to use this item.\r\nAre you sure you want to buy the item? |
| 497 | SP_497_SOME_ITEMS_HAVE_LIMITS_THAT_ARE_UNAVAILABLE_R_NFOR_YOU_TO_USE_THE_ITEM | Some items have limits that are unavailable\r\nfor you to use the item. |
| 498 | SP_498_YOU_ALREADY_HAVE_THE_ITEMS_SELECTED_ARE_R_NYOU_SURE_YOU_WANT_TO_BUY_THE_ITEMS_AG | You already have the item(s) selected. Are\r\nyou sure you want to buy the item(s) again? |
| 499 | SP_499_YOU_MAY_NOT_USE_THIS_ITEM_WITH_R_NTHE_WEAPON_YOU_ARE_EQUIPPED_WITH_R_NWILL_YOU_C | You may not use this item with\r\nthe weapon you are equipped with.\r\nWill you continue to make the purchase? |
| 500 | SP_500_ONLY_THE_RUDOLPH_PET_CAN_EQUIP_THIS_ITEM | Only the Rudolph pet can equip this item. |
| 501 | SP_501__R_NTHE_CURRENT_PET_YOU_HAVE_R_NCANNOT_WEAR_THIS_ITEM_R_NARE_YOU_SURE_YOU_WANT_T | \r\nThe current pet you have\r\ncannot wear this item.\r\nAre you sure you want to purchase this item? |
| 502 | SP_502_ONLY_PETS_ARE_ALLOWED_TO_WEAR_THIS_ITEM_R_NARE_YOU_SURE_YOU_WANT_TO_PURCHASE_THI | Only pets are allowed to wear this item.\r\nAre you sure you want to purchase this item? |
| 503 | SP_503_THIS_ITEM_CANNOT_BE_USED_WITHOUT_A_PET_AND_R_NTHIS_ITEM_CANNOT_BE_USED_ON_A_DECE | This item cannot be used without a pet, and\r\nthis item cannot be used on a deceased pet.\r\nAre you sure you want to make the purchase? |
| 504 | SP_504__R_NTHIS_ITEM_CANNOT_BE_USED_BY_THE_R_NPET_YOURE_CURRENTLY_RAISING_R_NARE_YOU_SU | \r\nThis item cannot be used by the\r\npet you're currently raising.\r\nAre you sure you want to make the purchase? |
| 505 | SP_505_THIS_IS_AN_ITEM_THAT_CANNOT_BE_USED_R_NIF_YOU_DO_NOT_HAVE_THE_MONSTER_R_NRIDING_ | This is an item that cannot be used\r\nif you do not have the Monster\r\nRiding skill. Would you\r\nstill like to purchase it? |
| 506 | SP_506_ARE_YOU_SURE_YOU_WANT_TO_BUY_THIS_ITEM | Are you sure you want to buy this item? |
| 507 | SP_507_TOTAL_D_NX | Total %d NX. |
| 508 | SP_508_MAKE_PURCHASE | Make purchase? |
| 509 | SP_509_THE_PURCHASE_HAS_FAILED | The purchase has failed. |
| 510 | SP_510_YOU_HAVE_PURCHASED_THE_FOLLOWING_ITEMS_R_NSOME_ITEMS_WERENT_PURCHASED | You have purchased the following items.\r\n(Some items weren't purchased) |
| 511 | SP_511_PLEASE_ENTER_THE_NAME_OF_THE_CHARACTER_AS_THE_RECEIVER_FOR_THIS_GIFT_R_NONLY_THE | Please enter the name of the character as the receiver for this gift.\r\nOnly the character within the same world are available for gifts. |
| 512 | SP_512_S_D_NX_R_NWILL_YOU_BUY_THIS_ITEM_AS_A_GIFT_FOR_R_NS | %s (%d NX)\r\nWill you buy this item as a gift for\r\n'%s'? |
| 513 | SP_513_CHECK_YOUR_SSN_AND__R_NPLEASE_TRY_AGAIN | Check your SSN and \r\nplease try again. |
| 514 | SP_514_CHECK_YOUR_BIRTHDAY_CODE_AND_R_NPLEASE_TRY_AGAIN | Check your birthday code and\r\nplease try again. |
| 515 | SP_515_CHECK_YOUR_PASSPORT_ID_AND_R_NPLEASE_TRY_AGAIN | Check your Passport ID and\r\nplease try again. |
| 516 | SP_516_YOU_CANNOT_USE_THIS_NAME__R_NPLEASE_TRY_AGAIN | You cannot use this name. \r\nPlease try again. |
| 517 | SP_517_THIS_NAME_IS_CURRENTLY_BEING_USED__R_NPLEASE_USE_ANOTHER_NAME | This name is currently being used. \r\nPlease use another name. |
| 518 | SP_518_THIS_NAME_CAN_BE_USED__R_NIF_YOU_WISH_TO_USE_THIS_NAME_R_NPLEASE_PRESS_OK | This name can be used. \r\nIf you wish to use this name,\r\nplease press OK. |
| 519 | SP_519_THIS_NAME_IS_SAME_AS_YOUR_CURRENT_NAME__R_NPLEASE_USE_ANOTHER_NAME | This name is same as your current name. \r\nPlease use another name. |
| 520 | SP_520_PRESS_THE_CHECK_BUTTON__R_NTO_CHECK_IF_YOU_CAN_USE_R_NTHE_NAME_OF_THE_CHARACTER | Press the CHECK button \r\nto check if you can use\r\nthe name of the character. |
| 521 | SP_521_THE_NAME_CHANGE_IS_ALREADY_SUBMITTED__R_NDUE_TO_THE_ITEM_PURCHASE | The name change is already submitted \r\ndue to the item purchase |
| 522 | SP_522_THIS_APPLIES_TO_THE_LIMITATIONS_ON_THE_REQUEST_R_NPLEASE_CHECK_IF_YOU_WERE_RECEN | This applies to the limitations on the request.\r\nPlease check if you were recently banned \r\nwithin 3 months. |
| 523 | SP_523_THIS_APPLIES_TO_THE_LIMITATIONS_ON_THE_REQUEST_R_NPLEASE_CHECK_IF_YOU_REQUESTED_ | This applies to the limitations on the request.\r\nPlease check if you requested \r\nfor the name change within a month. |
| 524 | SP_524_THERE_WILL_NOT_BE_ANY_REFUND__R_NFOR_CANCELING_THIS_REQUEST | There will not be any refund \r\nfor canceling this request. |
| 525 | SP_525_ARE_YOU_SURE_YOU_WANT_TO_CANCEL__R_NTHE_NAME_CHANGE_REQUEST | Are you sure you want to cancel \r\nthe name change request? |
| 526 | SP_526_YOUR_NAME_CHANGE_REQUEST__R_NHAS_BEEN_SUCCESSFULLY_SUBMITTED | Your name change request \r\nhas been successfully submitted. |
| 527 | SP_527_YOU_ALREADY_HAVE_THIS_ITEM_ON_YOUR_WISH_LIST | You already have this item on your wish list. |
| 528 | SP_528_YOUR_WISH_LIST_IS_FULL | Your wish list is full. |
| 529 | SP_529_YOU_HAVE_SUCCESSFULLY_MADE_THE_PURCHASE | You have successfully made the purchase. |
| 530 | SP_530_YOU_HAVE_GIVEN_S_R_N_D___S__ | You have given '%s'\r\n %d [ %s ]. |
| 531 | SP_531_YOU_HAVE_ADDED_SLOTS_TO_YOUR_ITEM_INVENTORY | You have added slots to your item inventory. |
| 532 | SP_532_YOU_HAVE_ADDED_A_NEW_CHARACTER_SLOT | You have added a new character slot. |
| 533 | SP_533_THE_CASH_ITEM_HAS_BEEN_DELETED | The cash item has been deleted. |
| 534 | SP_534_REQUEST_TIMED_OUT_R_NPLEASE_TRY_AGAIN | Request timed out.\r\nPlease try again. |
| 535 | SP_535_YOU_DONT_HAVE_ENOUGH_CASH | You don't have enough cash. |
| 536 | SP_536_YOU_CANT_BUY_SOMEONE_A_CASH_ITEM_GIFT_IF_YOURE_UNDER_14 | You can't buy someone a cash item gift if you're under 14. |
| 537 | SP_537_YOU_HAVE_EXCEEDED_THE_ALLOTTED_LIMIT_OF_PRICE_R_NFOR_GIFTS | You have exceeded the allotted limit of price\r\nfor gifts. |
| 538 | SP_538_PLEASE_CHECK_AND_SEE_IF_YOU_HAVE_EXCEEDED_R_NTHE_NUMBER_OF_CASH_ITEMS_YOU_CAN_HA | Please check and see if you have exceeded\r\nthe number of cash items you can have. |
| 539 | SP_539_PLEASE_CHECK_AND_SEE_R_NIF_THE_NAME_OF_THE_CHARACTER_IS_WRONG_R_NOR_IF_THE_ITEM_ | Please check and see\r\nif the name of the character is wrong,\r\nor if the item has gender restrictions. |
| 540 | SP_540_YOU_HAVE_REACHED_THE_DAILY_MAXIMUM__R_NPURCHASE_LIMIT_FOR_THE_CASH_SHOP | You have reached the daily maximum \r\npurchase limit for the Cash Shop. |
| 541 | SP_541_DUE_TO_GENDER_RESTRICTIONS_THE_COUPON__R_NIS_UNAVAILABLE_FOR_USE | Due to gender restrictions, the coupon \r\nis unavailable for use. |
| 542 | SP_542_THIS_COUPON_WAS_ALREADY_USED | This coupon was already used. |
| 543 | SP_543_THIS_COUPON_HAS_EXPIRED | This coupon has expired. |
| 544 | SP_544_THIS_COUPON_CAN_ONLY_BE_USED_AT_R_NNEXON_AFFILIATED_INTERNET_CAFES_R_NPLEASE_USE | This coupon can only be used at\r\nNexon-affiliated Internet Cafe's.\r\nPlease use the Nexon-affiliated Internet Cafe's. |
| 545 | SP_545_THIS_COUPON_IS_A_NEXON_AFFILIATED_INTERNET_CAFE_ONLY_COUPON_R_NAND_IT_HAD_ALREAD | This coupon is a Nexon-affiliated Internet Cafe-only coupon,\r\nand it had already been used. |
| 546 | SP_546_THIS_COUPON_IS_A_NEXON_AFFILIATED_INTERNET_CAFE_ONLY_COUPON_R_NAND_IT_HAD_ALREAD | This coupon is a Nexon-affiliated Internet Cafe-only coupon,\r\nand it had already been expired. |
| 547 | SP_547_PLEASE_CHECK_AND_SEE_IF__R_NTHE_COUPON_NUMBER_IS_RIGHT | Please check and see if \r\nthe coupon number is right. |
| 548 | SP_548_YOU_ARE_DISCONNECTED__R_NFROM_CASH_SHOP__R_NBECAUSE_YOU_PUT_WRONG__R_NCOUPON_NUM | You are disconnected \r\nfrom Cash Shop \r\nbecause you put wrong \r\nCoupon numbers three times. |
| 549 | SP_549_THE_COUPON_IS_EXPIRED | The coupon is expired. |
| 550 | SP_550_THE_COUPON_HAS_ALREADY_BEEN_USED | The coupon has already been used. |
| 551 | SP_551_THIS_COUPON_IS_ONLY_FOR_REGULAR_ITEMS_AND__R_NITS_UNAVAILABLE_TO_GIVE_AWAY_AS_A_ | This coupon is only for regular items, and \r\nit's unavailable to give away as a gift. |
| 552 | SP_552_THIS_COUPON_IS_ONLY_FOR_MAPLESTORY_AND_R_NIT_CANNOT_BE_GIFTED_TO_OTHERS | This coupon is only for MapleStory, and\r\nit cannot be gifted to others. |
| 553 | SP_553_THIS_ITEM_IS_ONLY_AVAILABLE_FOR_PURCHASE_BY_A_USER_AT_THE_PREMIUM_SERVICE_INTERN | This item is only available for purchase by a user at the premium service internet cafe. |
| 554 | SP_554_YOU_ARE_SENDING_A_GIFT_TO_AN_INVALID_RECIPIENT_R_NPLEASE_CHECK_THE_CHARACTER_NAM | You are sending a gift to an invalid recipient.\r\nPlease check the character name and gender. |
| 555 | SP_555_YOU_HAVE_EXCEEDED_THE_SPENDING_LIMIT_OF_NX | You have exceeded the spending limit of NX. |
| 556 | SP_556_THE_CASH_SHOP_IS_UNAVAILABLE_R_NDURING_THE_BETA_TEST_PHASE_R_NWE_APOLOGIZE_FOR_Y | The Cash Shop is unavailable\r\nduring the beta-test phase.\r\nWe apologize for your inconvenience. |
| 557 | SP_557_DUE_TO_AN_UNKNOWN_ERROR_R_NTHE_REQUEST_FOR_CASH_SHOP_HAS_FAILED | Due to an unknown error,\r\nthe request for Cash Shop has failed. |
| 558 | SP_558_BUY_WITH_MAPLEPOINTS | Buy with MaplePoints. |
| 559 | SP_559_THERE_ARE_REPEATING_CHARACTER_NAMES | There are repeating character names. |
| 560 | SP_560_DO_YOU_WANT_TO_GIVE_GIFTS_TO_D_PERSONS | Do you want to give gifts to %d persons? |
| 561 | SP_561_ALL_THE_GIFTS_HAVE_BEEN_SENT_R_ND_NX__R_NWAS_SPENT_IN_THE_PROCESS | All the gifts have been sent.\r\n%d NX \r\nwas spent in the process. |
| 562 | SP_562_THE_GIFTS_COULD_NOT_BE_SENT | The gifts could not be sent. |
| 563 | SP_563___S___R_NHAS_BEEN_SENT_TO_THE_CHARACTER_ABOVE_R_NIT_COULD_NOT_BE_SENT_TO_SEVERAL | [ %s ]\r\nhas been sent to the character above.\r\n(It could not be sent to several persons.)\r\n%d NX were spent in the process. |
| 564 | SP_564_ILL_BUY_THIS_AS_A_GIFT | I'll buy this as a gift. |
| 565 | SP_565_DELETING_THE_ITEM_CAN_ONLY_WORK_ON_CASH_EQUIPMENT_ITEMS_THAT_DONT_HAVE_A_LIMITED | Deleting the item can only work on cash equipment items that don't have a limited amount of time to use. |
| 566 | SP_566_THIS_ITEM_CANT_BE_DELETED | This item can't be deleted. |
| 567 | SP_567_CASH_ITEM_HAS_BEEN_DELETED_R_ND_ACCUMULATED_AS_MAPLEPOINTS | Cash item has been deleted.\r\n(%d accumulated as MaplePoints) |
| 568 | SP_568_THIS_IS_YOUR_WISH_LIST | This is your wish list. |
| 569 | SP_569_WELCOME_TO_MAPLESHOP | Welcome to MapleShop!!! |
| 570 | SP_570_THE_ITEM_IS_BEING_PREPARED_WERE_SORRY_FOR_THE_INCONVENIENCE | The item is being prepared. We're sorry for the inconvenience. |
| 571 | SP_571__NX |  NX |
| 572 | SP_572_D | (%d) |
| 573 | SP_573_THE_ITEM_HAS_BEEN_ADDED_TO_THE_WISH_LIST | The item has been added to the wish list. |
| 574 | SP_574__PET_S | [PET]%s |
| 575 | SP_575_ADDING_SLOTS_AT_THE_STORAGE | Adding slots at the storage |
| 576 | SP_576_ADD_D_SLOTS_FOR_THE_STORAGE_CURRENT_D_SLOTS_R_NMAXIMUM_OF_48_SLOTS_AVAILABLED_NX | Add %d slots for the storage (Current: %d slots)\r\nMaximum of 48 slots available.%d NX |
| 577 | SP_577_ADD_D_SLOTS_FOR_THE_STORAGE_CURRENT_D_R_NMAXIMUM_OF_48_SLOTS_AVAILABLE | Add %d slots for the storage (Current: %d)\r\nMaximum of 48 slots available. |
| 578 | SP_578_ADD_S_SLOTS | Add %s slots |
| 579 | SP_579_ADD_D_SLOTS_FOR_S_INVENTORY_CURRENT_D_SLOTS_R_NMAXIMUM_OF_D_SLOTS_AVAILABLED_NX | Add %d slots for %s inventory. (Current: %d slots)\r\nMaximum of %d slots available.%d NX. |
| 580 | SP_580_ADD_D_SLOTS_FOR_THE_S_INVENTORY_CURRENT_D_SLOTS_R_NMAXIMUM_OF_D_SLOTS_AVAILABLE | Add %d slots for the %s inventory. (Current: %d slots)\r\nMaximum of %d slots available. |
| 581 | SP_581_ADD_A_CHARACTER_SLOT_CURRENT__D_SLOTS_R_NYOU_MAY_HAVE_UP_TO_D_SLOTS | Add a character slot (Current : %d slots)\r\nYou may have up to %d slots. |
| 582 | SP_582_PLEASE_ENTER_THE_CORRECT_30_DIGIT_COUPON_NUMBER | Please enter the correct 30-digit coupon number. |
| 583 | SP_583_PLEASE_CHECK_THE_NAME_OF_THE_RECEIVER | Please check the name of the receiver. |
| 584 | SP_584_NAME_OF_THE_RECEIVING_CHARACTER | Name of the receiving character. |
| 585 | SP_585_YOU_HAVE_RECEIVED | You have received |
| 586 | SP_586__R_ND_S | \r\n%d %s |
| 587 | SP_587_D_MAPLEPOINTS | %d MaplePoints |
| 588 | SP_588_D_MESOS | %d mesos |
| 589 | SP_589_TO_S | To %s |
| 590 | SP_590_RECHARGE_NX | Recharge NX. |
| 591 | SP_591_THE_ITEM_IS_NOT_ON_SALE_NOW | The item is not on sale now. |
| 592 | SP_592_ARE_YOU_SURE_YOU_WANT_TO_LEAVE_THE_CASH_SHOP | Are you sure you want to leave the Cash Shop? |
| 593 | SP_593_YOU_DONT_HAVE_ENOUGH_NX_PREPAID | You don't have enough NX Prepaid |
| 594 | SP_594_AN_UNKNOWN_ERROR_HAS_OCCURRED_R_NWHILE_CHECKING_THE_PURCHASE_INFORMATION_ON_THE_ | An unknown error has occurred\r\nwhile checking the purchase information on the web shop.\r\nThis information is available at he game shop. |
| 595 | SP_595_FAILED_TO_CHECK_THE_PURCHASE_INFORMATION_ON_THE_WEB_SHOP_R_NDUE_TO_AN_ERROR_WHIL | Failed to check the purchase information on the web shop\r\ndue to an error while checking the cash item owned.\r\nThis information is available at the game shop. |
| 596 | SP_596_YOU_MAY_NOT_BRING_ANY_MORE_PURCHASED_ITEMS_FROM_THE_WEB_SHOP_R_NPLEASE_CHECK_AND | You may not bring any more purchased items from the web shop.\r\nPlease check and see if you have reached the limit on the cash items owned.\r\nGame shop is available. |
| 597 | SP_597_ERROR_FOUND_IN_THE_PURCHASE_ORDER_ON_THE_WEB_SHOP_R_NPLEASE_USE_THE_GAME_SHOP | Error found in the purchase order on the web shop.\r\nPlease use the game shop. |
| 598 | SP_598_STRENGTHENING_GEM | Strengthening Gem |
| 599 | SP_599_MONSTER_CRYSTAL | Monster Crystal |
| 600 | SP_600_DISASSEMBLE_EQUIPMENT | Disassemble Equipment |
| 601 | SP_601__ONE_HANDED_SWORD |  ONE-HANDED SWORD |
| 602 | SP_602__ONE_HANDED_AXE |  ONE-HANDED AXE |
| 603 | SP_603__ONEHANDED_MACE |  ONE_HANDED MACE |
| 604 | SP_604__DAGGER |  DAGGER |
| 605 | SP_605__WAND |  WAND |
| 606 | SP_606__STAFF |  STAFF |
| 607 | SP_607__TWO_HANDED_SWORD |  TWO-HANDED SWORD |
| 608 | SP_608__TWO_HANDED_AXE |  TWO-HANDED AXE |
| 609 | SP_609__TWO_HANDED_MACE |  TWO-HANDED MACE |
| 610 | SP_610__SPEAR |  SPEAR |
| 611 | SP_611__POLE_ARM |  POLE ARM |
| 612 | SP_612__BOW |  BOW |
| 613 | SP_613__CROSSBOW |  CROSSBOW |
| 614 | SP_614__CLAW |  CLAW |
| 615 | SP_615_KNUCKLE | KNUCKLE |
| 616 | SP_616_GUN | GUN |
| 617 | SP_617_HAT | HAT |
| 618 | SP_618_FACE_ACCESSORY | FACE ACCESSORY |
| 619 | SP_619_EYE_ACCESSORY | EYE ACCESSORY |
| 620 | SP_620_EARRING | EARRING |
| 621 | SP_621_OVERALL | OVERALL |
| 622 | SP_622_TOP | TOP |
| 623 | SP_623_BOTTOM | BOTTOM |
| 624 | SP_624_SHOES | SHOES |
| 625 | SP_625_GLOVE | GLOVE |
| 626 | SP_626_CAPE | CAPE |
| 627 | SP_627_SHIELD | SHIELD |
| 628 | SP_628_RING | RING |
| 629 | SP_629_PENDANT | PENDANT |
| 630 | SP_630_MEDAL | MEDAL |
| 631 | SP_631_BELT | BELT |
| 632 | SP_632_OVERALL_ARMOR | Overall Armor |
| 633 | SP_633_WEAPON | Weapon |
| 634 | SP_634_ONE_HANDED_WEAPON | One-handed Weapon |
| 635 | SP_635_TWO_HANDED_WEAPON | Two-handed Weapon |
| 636 | SP_636_POTION | Potion |
| 637 | SP_637_RETURN_SCROLL | Return Scroll |
| 638 | SP_638_WEAPON__ARMOR_SCROLL | Weapon & Armor Scroll |
| 639 | SP_639_ARROW_THROWING_STARS_AND_BULLETS | Arrow, Throwing Stars, and Bullets |
| 640 | SP_640_DROPPING | Dropping |
| 641 | SP_641_ORE | Ore |
| 642 | SP_642_GAME | Game |
| 643 | SP_643_STIMULATOR__HOW_TO_MAKE | Stimulator & How to Make |
| 644 | SP_644__FASTEST |  FASTEST |
| 645 | SP_645__FASTER |  FASTER |
| 646 | SP_646__FAST |  FAST |
| 647 | SP_647__NORMAL |  NORMAL |
| 648 | SP_648__SLOW |  SLOW |
| 649 | SP_649__SLOWER |  SLOWER |
| 650 | SP_650__SLOWEST |  SLOWEST |
| 651 | SP_651_ITS_A_GIFT_FROM_S | IT'S A GIFT FROM '%s' |
| 652 | SP_652_AVAILABLE_FOR_D_DAYS | AVAILABLE FOR %d DAYS. |
| 653 | SP_653_AVAILABLE_FOR_4_HOURS | AVAILABLE FOR 4 HOURS. |
| 654 | SP_654_AVAILABLE_FOR_D_HOURS | AVAILABLE FOR %d HOURS. |
| 655 | SP_655_USABLE_UNTIL_D_D_04D_AT_02D__02D | USABLE UNTIL %d-%d-%04d AT %02d : %02d |
| 656 | SP_656_ATTACK_SPEED_ | ATTACK SPEED : |
| 657 | SP_657_WEAPON_ATTACK_ | WEAPON ATTACK : |
| 658 | SP_658_MAGIC_ATTACK_ | MAGIC ATTACK : |
| 659 | SP_659_WEAPON_DEF_ | WEAPON DEF. : |
| 660 | SP_660_MAGIC_DEF_ | MAGIC DEF. : |
| 661 | SP_661_ACCURACY_ | ACCURACY : |
| 662 | SP_662_AVOIDABILITY_ | AVOIDABILITY : |
| 663 | SP_663_HANDS_ | HANDS : |
| 664 | SP_664_SPEED_ | SPEED : |
| 665 | SP_665_JUMP_ | JUMP : |
| 666 | SP_666_SWIMMING_SPEED__D | Swimming speed : +%d |
| 667 | SP_667_THE_RATE_OF_KNOCK_BACK_ | THE RATE OF KNOCK-BACK : |
| 668 | SP_668_NUMBER_OF_UPGRADES_AVAILABLE_ | NUMBER OF UPGRADES AVAILABLE : |
| 669 | SP_669_ADD_PREVENT_SLIPPING | ADD PREVENT SLIPPING |
| 670 | SP_670_ADD_PREVENT_COLDNESS | ADD PREVENT COLDNESS |
| 671 | SP_671_A_GIFT_FROM_S | A GIFT FROM '%s' |
| 672 | SP_672_A_COUPLE_RING_WITH__S | A COUPLE-RING WITH : %s |
| 673 | SP_673_THIS_IS_YOUR_FRIENDSHIP_RING_WITH_S | This is your Friendship Ring with '%s'. |
| 674 | SP_674__CURRENT_LEVEL_D_ | [Current Level %d] |
| 675 | SP_675__NEXT_LEVEL_D_ | [Next Level %d] |
| 676 | SP_676_DAYS_OF_MAGIC__D | DAYS OF MAGIC : %d |
| 677 | SP_677_DAYS_OF_MAGIC__DHRS_DMIN | DAYS OF MAGIC : %dhrs. %dmin. |
| 678 | SP_678_THE_WATER_OF_LIFE_HAS_DRIED_UP | THE WATER OF LIFE HAS DRIED UP. |
| 679 | SP_679_WATER_OF_LIFE_DRIES_UP_ON_D_D_04D_AT_02D__00 | WATER OF LIFE DRIES UP ON %d-%d-%04d AT %02d : 00 |
| 680 | SP_680_S___S__S___PREMIUM | \t%s / %s /%s / premium |
| 681 | SP_681_ONLINE_FRIENDS___TOTAL_REGISTERED_FRIENDS | Online Friends / Total Registered Friends |
| 682 | SP_682_OFFLINE_FRIENDS___TOTAL_REGISTERED_FRIENDS | Offline Friends / Total Registered Friends |
| 683 | SP_683__CURRENT_REGISTERED_FRIENDS_MAX_REGISTERED_FRIENDS_ | [Current Registered Friends/Max Registered Friends] |
| 684 | SP_684_ONLINE_GUILD_MEMBERS___TOTAL_REGISTERED_GUILD_MEMBERS | Online Guild Members / Total Registered Guild Members |
| 685 | SP_685_OFFLINE_GUILD_MEMBERS___TOTAL_REGISTERED_GUILD_MEMBERS | Offline Guild Members / Total Registered Guild Members |
| 686 | SP_686_SORT_THRU_NAME | Sort thru Name |
| 687 | SP_687_SORT_THRU_JOB | Sort thru Job |
| 688 | SP_688_SORT_THRU_LEVEL | Sort thru Level |
| 689 | SP_689_SORT_THRU_GRADE | Sort thru Grade |
| 690 | SP_690_CURRENTLY_REGISTERED_USERS___MAXIMUM_CAPACITY | Currently Registered Users / Maximum Capacity |
| 691 | SP_691_THE_USER_IS_CURRENTLY_AT_ANOTHER_LOCATION | The user is currently at another location. |
| 692 | SP_692_ONE_OF_A_KIND_ITEM | One-of-a-kind Item |
| 693 | SP_693_QUEST_ITEM | Quest Item |
| 694 | SP_694_PARTY_QUEST_ITEM | Party Quest Item |
| 695 | SP_695_UNTRADEABLE | Untradeable |
| 696 | SP_696_CAN_TRADE_1_TIME_TRADE_DISABLED_AFTER_TRANSACTION | Can trade 1 time (Trade disabled after transaction) |
| 697 | SP_697_SLOT_MERGER | Slot Merger |
| 698 | SP_698_ITEM_SORT | Item Sort |
| 699 | SP_699_TRADE_DISABLED_WHEN_EQUIPPED | Trade disabled when equipped |
| 700 | SP_700_UI_UIWINDOWIMG_TOOLTIP_EQUIP_DOT | UI/UIWindow.img/ToolTip/Equip/Dot |
| 701 | SP_701_BEGINNER | beginner |
| 702 | SP_702_WARRIOR | warrior |
| 703 | SP_703_MAGICIAN | magician |
| 704 | SP_704_BOWMAN | bowman |
| 705 | SP_705_THIEF | thief |
| 706 | SP_706_PIRATE | pirate |
| 707 | SP_707_BUDDY | buddy |
| 708 | SP_708_CURRENT_REGISTERED_FRIENDS___D_D_ | Current Registered Friends  [%d/%d] |
| 709 | SP_709_MY_LOCATION___S | My Location - %s |
| 710 | SP_710_SS_LOCATION___S | %s's Location - %s |
| 711 | SP_711_PLEASE_ENTER_THE_NAME_OF_THE_CHARACTER_R_NYOULL_ADD_AS_A_BUDDY | Please enter the name of the character\r\nyou'll add as a buddy. |
| 712 | SP_712_PLEASE_ENTER_THE_NAME_OF_THE_CHARACTER_R_NYOULL_INVITE_TO_YOUR_PARTY | Please enter the name of the character\r\nyou'll invite to your party. |
| 713 | SP_713_ARE_YOU_SURE_YOU_WANT_TO_DELETE_R_NS_FROM_THE_LIST | Are you sure you want to delete\r\n'%s' from the list? |
| 714 | SP_714_S_IS | %s is |
| 715 | SP_715_LOGGED_IN | logged in. |
| 716 | SP_716_YOURE_ON_THE_SAME_CH | You're on the same ch. |
| 717 | SP_717_WITH_S | with %s. |
| 718 | SP_718_CASH_SHOP | Cash Shop |
| 719 | SP_719_THE_REQUEST_WAS_DENIED_DUE_TO_AN_UNKNOWN_ERROR | The request was denied due to an unknown error. |
| 720 | SP_720_YOUR_BUDDY_LIST_IS_FULL | Your buddy list is full. |
| 721 | SP_721_THE_USERS_BUDDY_LIST_IS_FULL | The user's buddy list is full |
| 722 | SP_722_THAT_CHARACTER_IS_ALREADY_REGISTERED_AS_YOUR_BUDDY | That character is already registered as your buddy. |
| 723 | SP_723_THAT_CHARACTER_IS_NOT_REGISTERED | That character is not registered. |
| 724 | SP_724_GAMEMASTER_IS_NOT_AVAILABLE_AS_A_BUDDY | Gamemaster is not available as a buddy. |
| 725 | SP_725_PLEASE_ENTER_THE_NAME_OF_THE_CHARACTER_YOUD_LIKE_TO_ADD_TO_THE_BLACKLIST | Please enter the name of the character you'd like to add to the Blacklist. |
| 726 | SP_726_YOU_MAY_NOT_ENTER_YOURSELF_IN_THE_BLACKLIST | You may not enter yourself in the Blacklist |
| 727 | SP_727_THIS_CHARACTER_NAME_DOES_NOT_EXIST_R_NPLEASE_CHECK_AGAIN | This character name does not exist\r\nPlease check again |
| 728 | SP_728_THIS_NAME_HAS_ALREADY_BEEN_REGISTERED_R_NPLEASE_CHECK_AGAIN | This name has already been registered\r\nPlease check again |
| 729 | SP_729_YOU_CAN_USE_IT_AFTER_PURCHASING_THE_MEMO | You can use it after purchasing the Memo. |
| 730 | SP_730_YOU_MAY_ENTER_WITH_2_6_PARTY_MEMBERS | You may enter with 2-6 party members. |
| 731 | SP_731_YOUR_PARTY_ALREADY_HAS_D_OR_MORE_MEMBERS | Your party already has %d or more members. |
| 732 | SP_732_YOU_CAN_ONLY_SEARCH_FOR_PARTY_R_NMEMBERS_WITHIN_A_RANGE_OF_30_LEVELS | You can only search for party\r\nmembers within a range of 30 levels. |
| 733 | SP_733_CURRENTLY_THE_HIGHEST_LEVEL_IN_MAPLESTORY_R_NIS_AT_200 | Currently, the highest level in MapleStory\r\nis at 200! |
| 734 | SP_734_THE_RANGE_OF_LEVEL_FOR_SEARCH_HAS_TO_INCLUDE_YOUR_OWN_LEVEL | The range of level for search has to include your own level. |
| 735 | SP_735_THE_MIN_VALUE_IS_HIGHER_THAN_THE_MAX | The min. value is higher than the max! |
| 736 | SP_736_PLEASE_ENTER_THE_JOB_CLASS_YOUD_LIKE_TO_PARTY_WITH | Please enter the Job class you'd like to party with. |
| 737 | SP_737_PLEASE_SELECT_THE_ITEM_YOU_WISH_TO_MAKE_FIRST | Please select the item you wish to make first! |
| 738 | SP_738_YOU_DONT_HAVE_ENOUGH_ROOM_IN_YOUR_INVENTORY | You don't have enough room in your Inventory. |
| 739 | SP_739_YOU_CANNOT_ADD_THIS_ITEM | You cannot add this item. |
| 740 | SP_740_THIS_ITEM_HAS_ALREADY_BEEN_APPLIED | This item has already been applied. |
| 741 | SP_741_YOU_CANNOT_APPLY_ANY_MORE_INGREDIENTS | You cannot apply any more ingredients. |
| 742 | SP_742_YOU_CAN_ONLY_APPLY_THE_STRENGTHENING_GEM | You can only apply the Strengthening Gem. |
| 743 | SP_743_YOU_CANNOT_APPLY_THE_STRENGTHENING_GEM_ANYMORE | You cannot apply the Strengthening Gem anymore. |
| 744 | SP_744_YOU_CANNOT_APPLY_MORE_THAN_1_OF_THE_SAME_STRENGTHENING_GEMS | You cannot apply more than 1 of the same Strengthening Gems. |
| 745 | SP_745_YOU_CAN_ONLY_USE_THIS_STRENGTHENING_GEM_ON_WEAPON_ITEMS | You can only use this Strengthening Gem on weapon items. |
| 746 | SP_746_YOU_CAN_ONLY_APPLY_THE_STIMULATOR | You can only apply the Stimulator. |
| 747 | SP_747_YOU_CAN_ONLY_APPLY_1_STIMULATOR | You can only apply 1 Stimulator. |
| 748 | SP_748_YOU_CANNOT_APPLY_THIS_STIMULATOR | You cannot apply this Stimulator. |
| 749 | SP_749_YOU_CANNOT_USE_THIS_ITEM_AS_AN_INGREDIENT_TO_MAKE_THE_MONSTER_CRYSTAL | You cannot use this item as an ingredient to make the Monster Crystal. |
| 750 | SP_750_PRODUCTION_FAILED_DUE_TO_AN_UNKNOWN_ERROR | Production failed due to an unknown error. |
| 751 | SP_751_D_OF_S__HAVE_BEEN_PRODUCED | %d of %s  have been produced. |
| 752 | SP_752_YOU_HAVE_SUCCESSFULLY_DISASSEMBLED_THE_S | You have successfully disassembled the %s. |
| 753 | SP_753_PRODUCTION_FAILED | Production failed. |
| 754 | SP_754_THE_ITEM_HAS_BEEN_DESTROYED | The item has been destroyed. |
| 755 | SP_755_HOW_MANY_WOULD_YOU_LIKE_TO_APPLY | How many would you like to apply? |
| 756 | SP_756__WOULD_YOU_LIKE_TO_MAKE_THIS | : Would you like to make this? |
| 757 | SP_757_WOULD_YOU_LIKE_TO_MAKE_A_MONSTER_CRYSTAL | Would you like to make a Monster Crystal? |
| 758 | SP_758_WOULD_YOU_LIKE_TO_DISASSEMBLE | Would you like to disassemble? |
| 759 | SP_759_WOULD_YOU_LIKE_TO_MAKE_ | Would you like to make ??? |
| 760 | SP_760_APPLY_THE_DROP_ITEMS_HERE_R_N_TO_MAKE_THE_MONSTER_CRYSTAL | Apply the drop items here\r\n to make the Monster Crystal. |
| 761 | SP_761_APPLY_THE_EQUIPMENT_TO_BE_DISASSEMBLED_HERE_R_N_TO_ACQUIRE_THE_MONSTER_CRYSTAL | Apply the equipment to be disassembled here\r\n to acquire the Monster Crystal. |
| 762 | SP_762_PLEASE_TYPE_THE_NAME_OF_THE_CHARACTER_YOU_WANT_TO_INVITE | Please type the name of the character, you want to invite. |
| 763 | SP_763_FROM_S | from '%s' |
| 764 | SP_764_AN_INVITATION | An invitation |
| 765 | SP_765_BUDDY_REQUEST | Buddy request |
| 766 | SP_766_TRADE_REQUEST | Trade request |
| 767 | SP_767_PARTY_INVITE | Party invite |
| 768 | SP_768_GUILD_INVITE | Guild invite |
| 769 | SP_769_ITS_A_FAMILY_INVITATION | It's a Family Invitation. |
| 770 | SP_770_NEW_QUEST | New Quest! |
| 771 | SP_771_QUEST_COMPLETE | Quest Complete! |
| 772 | SP_772_MAPLE | Maple |
| 773 | SP_773_VICTORIA | Victoria |
| 774 | SP_774_THERES_NO_QUEST_INFO | There's no quest info. |
| 775 | SP_775_THE_ADMIN_ACCOUNT_CANT_THROW_AWAY_THE_MONEY | The Admin account can't throw away the money. |
| 776 | SP_776___YOU_HAVE_SENT_THE_INVITE_TO_S | - You have sent the invite to '%s'. |
| 777 | SP_777___S_CANT_BE_FOUND | - '%s' can't be found. |
| 778 | SP_778_S_IS_CURRENTLY_NOT_ACCEPTING_CHAT | '%s' is currently not accepting chat. |
| 779 | SP_779_S_DENIED_THE_REQUEST | '%s' denied the request. |
| 780 | SP_780___S_HAVE_REQUESTED_YOU_TO_JOIN_THE_CHAT | - '%s' have requested you to join the chat. |
| 781 | SP_781_S_HAVE_REQUESTED_YOU_TO__R_NJOIN_THE_CHAT_ROOM_R_NWILL_YOU_ACCEPT_IT | '%s' have requested you to \r\njoin the chat room.\r\nWill you accept it? |
| 782 | SP_782_PLEASE_ENTER_THE_PERSON_YOU_WANT_TO_WHISPER_TO | Please enter the person you want to whisper to. |
| 783 | SP_783_INCORRECT_ID | Incorrect ID. |
| 784 | SP_784_YOU_CANT_WHISPER_TO_YOURSELF | You can't whisper to yourself. |
| 785 | SP_785_PLEASE_SELECT_A_GROUP_TO_WHISPER_R_N | Please select a group to whisper.\r\n |
| 786 | SP_786_TO_ALL | To All |
| 787 | SP_787_TO_THE_PARTY | To the party |
| 788 | SP_788_TO_A_BUDDY | To a buddy |
| 789 | SP_789_TO_SPOUSE | To Spouse |
| 790 | SP_790_TO_THE_GUILD | To the Guild |
| 791 | SP_791_TO_GROUP | To Group |
| 792 | SP_792_PLEASE_ENTER_THE_ATTACHED_MESSAGE | Please enter the attached message |
| 793 | SP_793_THE_PET_ALREADY_HAS_A_NAME_R_NWILL_YOU_CHANGE_IT | The pet already has a name.\r\nWill you change it? |
| 794 | SP_794_PLEASE_NAME_YOUR_PET | Please name your pet. |
| 795 | SP_795_THAT_NAME_IS_NOT_AVAILABLE | That name is not available. |
| 796 | SP_796_DO_YOU_WANT_TO_NAME_YOUR_PET_R_NS | Do you want to name your pet\r\n'%s'? |
| 797 | SP_797_WILL_YOU_USE_THE_MESO_BAG_R_NTO_RECEIVE_D_MESOS | Will you use the meso bag\r\nto receive %d mesos? |
| 798 | SP_798_YOU_HAVE_USED_THE_MESO_BAG_R_NTO_RECEIVE_D_MESOS | You have used the meso bag\r\nto receive %d mesos. |
| 799 | SP_799_YOU_HAVE_FAILED_TO_USE_THE_MESO_BAG | You have failed to use the meso bag. |
| 800 | SP_800___MAPLE_MESSENGER_HELP__ | [ Maple Messenger Help ] |
| 801 | SP_801_INVITE___INVITE_CHARACTER_NAME | Invite : /invite character-name |
| 802 | SP_802_END___Q | End : /q |
| 803 | SP_803___S_HAS_ENTERED | - '%s' has entered. |
| 804 | SP_804___YOU_HAVE_BEEN_UNABLE_TO_JOIN_THE_INVITED_CHAT_ROOM | - You have been unable to join the invited chat room. |
| 805 | SP_805___S_HAVE_LEFT_THE_ROOM | - '%s' have left the room. |
| 806 | SP_806___PLEASE_ENTER_THE_USERS_NAME | - Please enter the user's name. |
| 807 | SP_807___THE_CHARACTER_NAME_IS_INVALID | - The character name is invalid. |
| 808 | SP_808__IS_TYPING |  is typing. |
| 809 | SP_809_THE_NAME_OF_THE_CHARACTER_NEEDS_TO_BE_AT_LEAST_D_LETTERS_LONG | The name of the character needs to be at least %d letters long. |
| 810 | SP_810_THE_NAME_OF_THE_CHARACTER_NEEDS_TO_BE_LESS_THAN_D_LETTERS_LONG | The name of the character needs to be less than %d letters long. |
| 811 | SP_811_THE_NAME_OF_THE_CHARACTER_SHOULD_NOT_HAVE_ANY_BLANKS_IN_BETWEEN_THE_LETTERS | The name of the character should not have any blanks in between the letters. |
| 812 | SP_812_THERES_A_LETTER_YOU_CANT_USE_ON_YOUR_CHARACTER_NAME | There's a letter you can't use on your character name. |
| 813 | SP_813_THERES_A_WORD_YOU_CANT_USE_ON_YOUR_CHARACTER_NAME | There's a word you can't use on your character name. |
| 814 | SP_814_THE_NAME_CANNOT_BE_USED | The name cannot be used. |
| 815 | SP_815_MAPLESTORY_FOLDER | MapleStory Folder |
| 816 | SP_816_DESKTOP | Desktop |
| 817 | SP_817_ONLY_NUMBERS_ARE_ALLOWED | Only numbers are allowed. |
| 818 | SP_818_YOU_MAY_ONLY_ENTER_A_NUMBER__R_NEQUAL_TO_OR_HIGHER_THAN_D | You may only enter a number \r\nequal to or higher than %d. |
| 819 | SP_819_YOU_MAY_ONLY_ENTER_A_NUMBER__R_NEQUAL_TO_OR_LOWER_THAN_D | You may only enter a number \r\nequal to or lower than %d. |
| 820 | SP_820_PLEASE_ENTER_MORE_THAN_D_DIGITS | Please enter more than %d digits. |
| 821 | SP_821_PLEASE_ENTER_AT_LEAST_D_CHARACTERS | Please enter at least %d characters. |
| 822 | SP_822_FOR_THE_SAKE_OF_PRIVACY_PLEASE_ENTER_THE_SSD | For the sake of privacy, please enter the SSD. |
| 823 | SP_823_PLEASE_TYPE_THE_NAME_OF_THE_GUILD | Please type the name of the guild. |
| 824 | SP_824_IT_CANNOT_BE_USED_PLEASE_CHECK_IT_AGAIN | It cannot be used. Please check it again. |
| 825 | SP_825_THIS_NAME_IS_CURRENTLY_IN_THE_USE_PLEASE_USE_OTHER_ONES | This name is currently in the use. Please use other ones. |
| 826 | SP_826_FOR_THE_SAKE_OF_PRIVACY_R_NPLEASE_ENTER_YOUR_8_DIGIT_BIRTHDAY_CODE_R_NEX_YEAR_MO | For the sake of privacy,\r\nplease enter your 8-digit birthday code.\r\nex) year/month/day -> 19820911 |
| 827 | SP_827_PERSONAL_INFO | Personal Info. |
| 828 | SP_828_FAME_ | Fame + |
| 829 | SP_829_FAME__ | Fame - |
| 830 | SP_830_TRADE_REQUEST | Trade Request |
| 831 | SP_831_INVITE_TO_THE_PARTY | Invite to the party |
| 832 | SP_832_SAVE_NAME | Save name |
| 833 | SP_833_UNABLE_TO_CONNECT_DUE_TO_SYSTEM_ERROR | Unable to connect due to system error. |
| 834 | SP_834_THE_ID_IS_DELETED_OR_RESTRICTED_FROM_USAGE_AT_THIS_POINT | The ID is deleted or restricted from usage at this point. |
| 835 | SP_835_THE_ID_IS_NOT_REGISTERRED | The ID is not registerred. |
| 836 | SP_836_THE_ID_IS_EITHER_ALREADY_ONLINE_R_N_OR_THE_SERVER_IS_IN_CHECK_RIGHT_NOW | The ID is either already online,\r\n or the server is in check right now. |
| 837 | SP_837_SAVE_EMAIL_ADDRESS | Save Email Address |
| 838 | SP_838_THE_EMAIL_ADDRESS_NEEDS_TO_BE_AT_LEAST_D_LETTERS_LONG | The email address needs to be at least %d letters long. |
| 839 | SP_839_THE_EMAIL_ADDRESS_NEEDS_TO_BE_NO_MORE_THAN_D_LETTER_LONG | The email address needs to be no more than %d letter long. |
| 840 | SP_840_ALL_EMAIL_ADDRESSES_NEED_TO_INCLUDE__R_NEX_MAPLEWIZETCOM | All email addresses need to include "@".\r\n(ex) maple@wizet.com |
| 841 | SP_841_ALL_EMAIL_ADDRESSES_NEED_TO_INCLUDE__R_NEX_MAPLEWIZETCOM | All email addresses need to include "."\r\n(ex) maple@wizet.com |
| 842 | SP_842_THE_PASSWORD_NEEDS_TO_BE_NO_MORE_THAN_D_LETTERS_LONG | The password needs to be no more than %d letters long. |
| 843 | SP_843_YOU_NEED_TO_BE_OVER_20_TO_ENTER | You need to be over 20 to enter. |
| 844 | SP_844_YOU_ARE_NOT_IN_AN_AUTHORIZED_INTERNET_CAFE | You are not in an authorized Internet Cafe. |
| 845 | SP_845_THE_MAXIMUM_NUMBER_OF_USERS_ALLOWED_ON_THIS_IP_ADDRESS_HAVE_EXCEEDED | The maximum number of users allowed on this IP address have exceeded. |
| 846 | SP_846_THE_IP_HAS_OUTRUN_THE_CONTRACT | The IP has outrun the contract. |
| 847 | SP_847_ITS_NOT_AN_AVAILABLE_TIME_TO_PLAY_THIS_GAME | It's not an available time to play this game. |
| 848 | SP_848_ARE_YOU_SURE_YOU_WANT_TO_BUY_IT | Are you sure you want to buy it? |
| 849 | SP_849_HOW_MANY_ARE_YOU_WILLING_TO_BUY | How many are you willing to buy? |
| 850 | SP_850_ARE_YOU_SURE_YOU_WANT_TO_SELL_IT | Are you sure you want to sell it? |
| 851 | SP_851_HOW_MANY_ARE_YOU_WILLING_TO_SELL | How many are you willing to sell? |
| 852 | SP_852_YOU_DONT_HAVE_ENOUGH_IN_STOCK | You don't have enough in stock. |
| 853 | SP_853_PLEASE_CHECK_IF_YOUR_INVENTORY_IS_FULL_OR_NOT | Please check if your inventory is full or not. |
| 854 | SP_854_YOU_CANNOT_CARRY_MORE_THAN_ONE_FOR_THIS_ITEM | You cannot carry more than one for this item. |
| 855 | SP_855_DUE_TO_AN_ERROR_THE_TRADE_DID_NOT_HAPPEN | Due to an error, the trade did not happen. |
| 856 | SP_856_S_MESO___RECHRG__D | %s meso / rechrg : %d |
| 857 | SP_857_ARE_YOU_SURE_YOU_WANT_TO_RECHARGE_IT | Are you sure you want to recharge it? |
| 858 | SP_858_ARE_YOU_SURE_YOU_WANT_TO__R_NRECOVER_THE_ITEMS_FROM_STORAGE | Are you sure you want to \r\nrecover the item(s) from storage? |
| 859 | SP_859_RECOVERING_THE_ITEMS_WILL_COST_D_MESOS_R_NARE_YOU_SURE_YOU_WANT_TO_RECOVER_THIS | Recovering the items will cost %d mesos.\r\nAre you sure you want to recover this? |
| 860 | SP_860_ARE_YOU_SURE_YOU_WANT_TO_STORE_THE_ITEMS | Are you sure you want to store the item(s)? |
| 861 | SP_861_STORING_THIS_WILL_COST_D_MESOS_R_NARE_YOU_SURE_YOU_WANT_TO_STORE_THIS | Storing this will cost %d mesos.\r\nAre you sure you want to store this? |
| 862 | SP_862_HOW_MANY_WILL_YOU_STORE | How many will you store? |
| 863 | SP_863_HOW_MUCH_WILL_YOU_TAKE_OUT | How much will you take out? |
| 864 | SP_864_HOW_MUCH_WILL_YOU_SAVE | How much will you save? |
| 865 | SP_865_THE_STORAGE_IS_FULL | The storage is full. |
| 866 | SP_866_ITEM_COULD_NOT_BE_RETRIEVED_R_NBECAUSE_THERE_WAS_AN_ITEM_THAT_R_NCOULD_ONLY_BE_A | Item could not be retrieved\r\nbecause there was an item that\r\ncould only be acquired once. |
| 867 | SP_867_YOU_CANNOT_TRADE_THIS_ITEM_AFTER_EQUIPPING_R_N_DO_YOU_STILL_WISH_TO_EQUIP | You cannot trade this item after equipping.\r\n Do you still wish to equip? |
| 868 | SP_868_YOUR_STATS_ARE_TOO_LOW_TO_EQUIP_THIS_ITEM | Your stats are too low to equip this item. |
| 869 | SP_869_ONLY_ONE_COUPLE_RING_CAN_BE_EQUIPPED | Only one couple-ring can be equipped. |
| 870 | SP_870_YOU_MAY_ONLY_EQUIP_R_NONE_FRIENDSHIP_RING_AT_A_TIME | You may only equip\r\none Friendship Ring at a time. |
| 871 | SP_871_YOU_NEED_TO_EQUIP_YOURSELF_WITH_AN_ITEM_R_NTO_USE_IT | You need to equip yourself with an item\r\nto use it. |
| 872 | SP_872_YOU_ARE_ABLE_TO_USE_THIS_ONLY_R_NWHEN_YOU_ARE_WEARING_A_SHIELD | You are able to use this only\r\nwhen you are wearing a shield. |
| 873 | SP_873_YOU_MUST_HAVE_A_TAMED_MONSTER_R_NIN_ORDER_TO_EQUIP_THIS | You must have a tamed monster\r\nin order to equip this. |
| 874 | SP_874_YOU_MUST_BE_EQUIPPED_WITH_A_SADDLE_R_NTO_EQUIP_THIS | You must be equipped with a saddle\r\nto equip this. |
| 875 | SP_875_THIS_ITEM_IS_NOT_AVAILABLE_WITH_THE_WEAPON_R_NYOURE_CURRENLY_USING | This item is not available with the weapon\r\nyou're currenly using. |
| 876 | SP_876_YOU_MAY_NOT_USE_THE_ITEM_WITH_THE_R_NCASH_ITEM_YOURE_CURRENTLY_EQUIPPED_WITH_R_N | You may not use the item with the\r\ncash item you're currently equipped with.\r\nPlease unequip that item and try again. |
| 877 | SP_877_THIS_ITEM_CANNOT_BE_EQUIPPED_WITH_YOUR_CURRENT_PET | This item cannot be equipped with your current pet. |
| 878 | SP_878_YOU_CANNOT_CHANGE_YOUR_EQUIPMENT_ON_THIS_MAP | You cannot change your equipment on this map. |
| 879 | SP_879_YOU_DO_NOT_CURRENTLY_HAVE_A_PET_THAT_IS_ALIVE | You do not currently have a pet that is alive. |
| 880 | SP_880_THE_PET_IS_UNABLE_TO_WEAR_THE_EQUIPMENT | The pet is unable to wear the equipment. |
| 881 | SP_881_THE_ADMIN_CANT_DROP_ITEMS | The admin can't drop items. |
| 882 | SP_882_YOU_CANT_DROP_THIS_ITEM | You can't drop this item. |
| 883 | SP_883_YOU_CANNOT_CHANGE_YOUR_STATS_ON_THIS_MAP | You cannot change your stats on this map. |
| 884 | SP_884_YOU_CANNOT_USE_THAT_SKILL_ON_THIS_MAP | You cannot use that skill on this map. |
| 885 | SP_885_HOW_MANY_WILL_YOU_DROP | How many will you drop? |
| 886 | SP_886_THIS_ITEM_IS_UNAVAILABLE_TO_TRADE_WITH_OTHERS | This item is unavailable to trade with others. |
| 887 | SP_887_HOW_MANY_WILL_YOU_TRADE | How many will you trade? |
| 888 | SP_888__WELCOME__WELCOME_TO_MAPLESTORY | [Welcome] Welcome to MapleStory!! |
| 889 | SP_889__MAPLETIP_ | [MapleTip] |
| 890 | SP_890__NOTICE_ | [Notice] |
| 891 | SP_891_REPEATING_THE_SAME_LINE_OVER_AND_OVER_R_NCAN_NEGATIVELY_AFFECT_OTHER_USERS | Repeating the same line over and over\r\ncan negatively affect other users. |
| 892 | SP_892_TOO_MUCH_CHATTING_CAN_DISRUPT_R_NOTHER_PLAYERS_ABILITY_TO_PLAY_THE_GAME | Too much chatting can disrupt\r\nother players' ability to play the game. |
| 893 | SP_893_YOU_ARE_CURRENTLY_BLOCKED_FROM_CHATTING | You are currently blocked from chatting. |
| 894 | SP_894_JAN | Jan |
| 895 | SP_895_FEB | Feb |
| 896 | SP_896_MAR | Mar |
| 897 | SP_897_APR | Apr |
| 898 | SP_898_MAY | May |
| 899 | SP_899_JUN | Jun |
| 900 | SP_900_JUL | Jul |
| 901 | SP_901_AUG | Aug |
| 902 | SP_902_SEP | Sep |
| 903 | SP_903_OCT | Oct |
| 904 | SP_904_NOV | Nov |
| 905 | SP_905_DEC | Dec |
| 906 | SP_906_UNABLE_TO_CONNECT | Unable to connect. |
| 907 | SP_907_TIME_REMAINING_02D_MIN_02D_SEC | Time remaining: %02d min. %02d sec. |
| 908 | SP_908_YOU_ARE_UNABLE_TO_WHISPER_AT_THE_EVENT_MAP | You are unable to whisper at the event map. |
| 909 | SP_909__ANSWER | [Answer: |
| 910 | SP_910_UNABLE_TO_START_DUE_TO_THE_LACK_OF_NUMBER_OF_USERS | Unable to start due to the lack of number of users. |
| 911 | SP_911_THE_PRIZE_HASNT_BEEN_SET_YET | The prize hasn't been set yet. |
| 912 | SP_912_CONGRATUATIONS_YOU_HAVE_REACHED_THE_ROUND_OF_D | Congratuations! You have reached the round of %d. |
| 913 | SP_913_CONGRATULATIONS_YOU_HAVE_NOW_REACHED_THE_FINALS | Congratulations! You have now reached the finals. |
| 914 | SP_914_CONGRATUATIONS_YOU_ARE_THE_CHAMP | Congratuations, you are the CHAMP!!! |
| 915 | SP_915_YOU_HAVE_SUCCESSFULLY_SET_THE_PRIZE | You have successfully set the prize. |
| 916 | SP_916_YOU_HAVE_FAILED_THE_SET_THE_PRIZE_R_NPLEASE_CHECK_THE_ITEM_NUMBER_AGAIN | You have failed the set the prize.\r\nPlease check the item number again. |
| 917 | SP_917_________PRIZE_________R_N1ST__S_R_N2ND__S | --------PRIZE--------\r\n1st : %s\r\n\t2nd : %s |
| 918 | SP_918_S_02D02D_02D_SEC | %s %02d:%02d %02d sec. |
| 919 | SP_919_DUE_TO_AN_ERROR_WITHIN_THE_PATCH_THE_INSTALLATION_WILL_STOP_RIGHT_THIS_MINUTE_PL | Due to an error within the patch, the installation will stop right this minute. Please download the newest version of the client. |
| 920 | SP_920_DID_YOU_ENJOY_YOUR_TIME_WITH_MAPLESTORY_R_N_R_NIF_THE_SCREEN_TURNED_PITCH_BLACK_ | Did you enjoy your time with MapleStory?\r\n\r\nIf the screen turned pitch-black\r\nor you see warnings such as the monitor frequency being over the range,\r\n\r\nthen go to[start->program->MapleStory->Setup] to set-up your frequency level. |
| 921 | SP_921_EBASIC_CONFIGURATION_FOR_MAPLESTORYN_R_N_R_NFUI_UIWINDOWIMG_UTILDLGEX_NOTICE | #eBasic Configuration for MapleStory#n\r\n\r\n#fUI/UIWindow.img/UtilDlgEx/notice# |
| 922 | SP_922___________________HELP__________________ | ----------------- HELP ----------------- |
| 923 | SP_923__PARTYINFO__SHOW_THE_CURRENT_PARTY_INFORMATION | /partyinfo : Show the current party information. |
| 924 | SP_924__MAKEPARTY__CREATE_A_NEW_PARTY | /makeparty : create a new party |
| 925 | SP_925__LEAVEPARTY__IF_YOU_ARE_A_MEMBER_OF_THE_PARTY_ITLL_LET_YOU_LEAVE | /leaveparty : If you are a member of the party, it'll let you leave.. |
| 926 | SP_926__PARTYINVITE_CHARACTER_NAME__INVITE_TO_OUR_PARTY_ONLY_FOR_THE_PARTY_LEADER | /partyinvite character-name : invite to our party (only for the party leader) |
| 927 | SP_927__PARTYEXPEL_CHARACTER_NAME_EXPEL_SOMEONE_FROM_THE_PARTY_ONLY_FOR_THE_PARTY_LEADE | /partyexpel character-name: Expel someone from the party (only for the party leader) |
| 928 | SP_928__GUILDINFO__GIVE_INFORMATION_ABOUT_THE_GUILD | /guildinfo : Give information about the guild |
| 929 | SP_929__LEAVEGUILD__QUIT_THE_GUILD | /leaveguild : Quit the guild |
| 930 | SP_930__GUILDINVITE__INVITE_THE_USER_TO_THE_GUILD_ONLY_AVAILABLE_TO_THE_MASTER | /guildinvite : Invite the user to the guild (only available to the master) |
| 931 | SP_931__GUILDEXPEL__EXPEL_THE_USER_FROM_THE_GUILD_ONLY_AVAILABLE_TO_THE_MASTER | /guildexpel : Expel the user from the guild (only available to the master) |
| 932 | SP_932__FIND_CHARACTERNAME__GIVE_INFORMATION_ABOUT_THE_CHRACTER_WHETHER_HE_OR_SHE_IS_ON | /find CharacterName : Give information about the chracter whether he or she is on-line or not. |
| 933 | SP_933__GAMETRADE_CHARACTERNAME_REQUEST_TO_TRADE_GAME_ITEMS | /gametrade charactername: Request to trade game items. |
| 934 | SP_934__TRADECASH_CHARACTERNAME_REQUEST_TO_TRADE_CASH_ITEMS | /tradecash Charactername: Request to trade cash items. |
| 935 | SP_935__BUDDY_TALK_TO_YOUR_BUDDY | /buddy: Talk to your buddy. |
| 936 | SP_936__PARTY_TALK_TO_PARTY_MEMBERS | /party: Talk to party members. |
| 937 | SP_937__GUILD_TALK_TO_YOUR_GUILD_MEMBERS | /guild: Talk to your guild members. |
| 938 | SP_938__WHISPER_CHARACTERNAME_SEND_A_WHISPER_IF_NO_NAME_IS_WRITTEN_ITLL_BE_DIRECTED_TO_ | /whisper charactername: Send a whisper (if no name is written, it'll be directed to your last contact). |
| 939 | SP_939__ALL_REGULAR_CHAT_NOT_A_WHISPER | /all: Regular chat, not a whisper. |
| 940 | SP_940__OPEN_UP_THE_CHAT_WINDOW_AND_PRESS_TAB_OR_CLOSE_THE_CHAT_WINDOW_AND | * Open up the chat window and press tab, or close the chat window and |
| 941 | SP_941_SELECT | Select.... |
| 942 | SP_942_MALE | Male |
| 943 | SP_943_FEMALE | Female |
| 944 | SP_944_DHLRPDLSQJWJS | dhlrPdlsqjwjs |
| 945 | SP_945_WVSCLIENTMTX | WvsClientMtx |
| 946 | SP_946_CMAPIMG | cmap.img |
| 947 | SP_947_CMAPIMG_D | cmap.img/%d |
| 948 | SP_948_SMAPIMG | smap.img |
| 949 | SP_949_ZMAPIMG | zmap.img |
| 950 | SP_950_MAP | map |
| 951 | SP_951_NEXONADBALLON | NexonADBallon |
| 952 | SP_952_NEXONADBROWSER | NexonADBrowser |
| 953 | SP_953_AD | ad. |
| 954 | SP_954_MOB_07DIMG | Mob/%07d.img |
| 955 | SP_955_NPC_07DIMG | Npc/%07d.img |
| 956 | SP_956_CANVAS | Canvas |
| 957 | SP_957_PROPERTY | Property |
| 958 | SP_958_SHAPE2DVECTOR2D | Shape2D#Vector2D |
| 959 | SP_959_ORIGIN | origin |
| 960 | SP_960_DEFAULT | default |
| 961 | SP_961_INFO | info |
| 962 | SP_962_LINK | link |
| 963 | SP_963_Z0 | z0 |
| 964 | SP_964_Z1 | z1 |
| 965 | SP_965_U | %u |
| 966 | SP_966_SS | %s%s |
| 967 | SP_967__N | \n |
| 968 | SP_968_EFFECT_BASICEFFIMG | Effect/BasicEff.img |
| 969 | SP_969_NORED0 | NoRed0 |
| 970 | SP_970_NORED1 | NoRed1 |
| 971 | SP_971_NOBLUE0 | NoBlue0 |
| 972 | SP_972_NOBLUE1 | NoBlue1 |
| 973 | SP_973_NOVIOLET0 | NoViolet0 |
| 974 | SP_974_NOVIOLET1 | NoViolet1 |
| 975 | SP_975_NOCRI0 | NoCri0 |
| 976 | SP_976_NOCRI1 | NoCri1 |
| 977 | SP_977_X | x |
| 978 | SP_978_Y | y |
| 979 | SP_979_Z | z |
| 980 | SP_980_FALL | fall |
| 981 | SP_981_DURATION | duration |
| 982 | SP_982_FIXED | fixed |
| 983 | SP_983_BULLET | bullet |
| 984 | SP_984_MISS | Miss |
| 985 | SP_985_EFFECT_QUESTIMG_D | Effect/Quest.img/%d |
| 986 | SP_986_ETC_EMOTIONEFFECT_S | Etc/EmotionEffect/%s |
| 987 | SP_987_BLINK | blink |
| 988 | SP_988_SMILE | smile |
| 989 | SP_989_TROUBLED | troubled |
| 990 | SP_990_ANGRY | angry |
| 991 | SP_991_BEWILDERED | bewildered |
| 992 | SP_992_STUNNED | stunned |
| 993 | SP_993_VOMIT | vomit |
| 994 | SP_994_OOPS | oops |
| 995 | SP_995_CHEERS | cheers |
| 996 | SP_996_CHU | chu |
| 997 | SP_997_WINK | wink |
| 998 | SP_998_PAIN | pain |
| 999 | SP_999_GLITTER | glitter |
| 1000 | SP_1000_BLAZE | blaze |
| 1001 | SP_1001_SHINE | shine |
| 1002 | SP_1002_LOVE | love |
| 1003 | SP_1003_DESPAIR | despair |
| 1004 | SP_1004_HUM | hum |
| 1005 | SP_1005_BOWING | bowing |
| 1006 | SP_1006_DAM | dam |
| 1007 | SP_1007_ANGRYSHORT | angry_short |
| 1008 | SP_1008_WALK1 | walk1 |
| 1009 | SP_1009_WALK2 | walk2 |
| 1010 | SP_1010_ALERT | alert |
| 1011 | SP_1011_ALERT2 | alert2 |
| 1012 | SP_1012_SWINGO1 | swingO1 |
| 1013 | SP_1013_SWINGO2 | swingO2 |
| 1014 | SP_1014_SWINGO3 | swingO3 |
| 1015 | SP_1015_SWINGOF | swingOF |
| 1016 | SP_1016_SWINGT1 | swingT1 |
| 1017 | SP_1017_SWINGT2 | swingT2 |
| 1018 | SP_1018_SWINGT3 | swingT3 |
| 1019 | SP_1019_SWINGTF | swingTF |
| 1020 | SP_1020_SWINGP1 | swingP1 |
| 1021 | SP_1021_SWINGP2 | swingP2 |
| 1022 | SP_1022_SWINGPF | swingPF |
| 1023 | SP_1023_STABO1 | stabO1 |
| 1024 | SP_1024_STABO2 | stabO2 |
| 1025 | SP_1025_STABOF | stabOF |
| 1026 | SP_1026_STABT1 | stabT1 |
| 1027 | SP_1027_STABT2 | stabT2 |
| 1028 | SP_1028_STABTF | stabTF |
| 1029 | SP_1029_PRONESTAB | proneStab |
| 1030 | SP_1030_PRONE | prone |
| 1031 | SP_1031_SHOOT1 | shoot1 |
| 1032 | SP_1032_SHOOT2 | shoot2 |
| 1033 | SP_1033_SHOOT6 | shoot6 |
| 1034 | SP_1034_SHOOTF | shootF |
| 1035 | SP_1035_STAND | stand |
| 1036 | SP_1036_STAND0 | stand0 |
| 1037 | SP_1037_STAND1 | stand1 |
| 1038 | SP_1038_STAND2 | stand2 |
| 1039 | SP_1039_STAND3 | stand3 |
| 1040 | SP_1040_FLY | fly |
| 1041 | SP_1041_LADDER | ladder |
| 1042 | SP_1042_LADDER2 | ladder2 |
| 1043 | SP_1043_ROPE | rope |
| 1044 | SP_1044_ROPE2 | rope2 |
| 1045 | SP_1045_SIT | sit |
| 1046 | SP_1046_TIRED | tired |
| 1047 | SP_1047_SAVAGE | savage |
| 1048 | SP_1048_MAGIC1 | magic1 |
| 1049 | SP_1049_THROW | throw |
| 1050 | SP_1050_BALL | ball |
| 1051 | SP_1051_BALL1 | ball1 |
| 1052 | SP_1052_BALL2 | ball2 |
| 1053 | SP_1053_HIT1 | hit1 |
| 1054 | SP_1054_HIT2 | hit2 |
| 1055 | SP_1055_HITF | hitF |
| 1056 | SP_1056_ATTACK1 | attack1 |
| 1057 | SP_1057_ATTACK2 | attack2 |
| 1058 | SP_1058_ATTACK3 | attack3 |
| 1059 | SP_1059_ATTACK4 | attack4 |
| 1060 | SP_1060_ATTACK5 | attack5 |
| 1061 | SP_1061_ATTACK6 | attack6 |
| 1062 | SP_1062_ATTACK7 | attack7 |
| 1063 | SP_1063_ATTACK8 | attack8 |
| 1064 | SP_1064_ATTACKF | attackF |
| 1065 | SP_1065_DIE | die |
| 1066 | SP_1066_DIE1 | die1 |
| 1067 | SP_1067_DIE2 | die2 |
| 1068 | SP_1068_DIEF | dieF |
| 1069 | SP_1069_BOMB | bomb |
| 1070 | SP_1070_REST0 | rest0 |
| 1071 | SP_1071_REST1 | rest1 |
| 1072 | SP_1072_HUNGRY | hungry |
| 1073 | SP_1073_HANG | hang |
| 1074 | SP_1074_BROW | brow |
| 1075 | SP_1075_NAVEL | navel |
| 1076 | SP_1076_FACE | face |
| 1077 | SP_1077_MUZZLE | muzzle |
| 1078 | SP_1078_ACT | act |
| 1079 | SP_1079_FRAME | frame |
| 1080 | SP_1080_SUMMONED | summoned |
| 1081 | SP_1081_ALERT3 | alert3 |
| 1082 | SP_1082_ALERT4 | alert4 |
| 1083 | SP_1083_ALERT5 | alert5 |
| 1084 | SP_1084_AVENGER | avenger |
| 1085 | SP_1085_ASSAULTER | assaulter |
| 1086 | SP_1086_BURSTER1 | burster1 |
| 1087 | SP_1087_BURSTER2 | burster2 |
| 1088 | SP_1088_PRONE2 | prone2 |
| 1089 | SP_1089_ASSASSINATION | assassination |
| 1090 | SP_1090_ASSASSINATIONS | assassinationS |
| 1091 | SP_1091_RUSH | rush |
| 1092 | SP_1092_RUSH2 | rush2 |
| 1093 | SP_1093_HANDGUN | handgun |
| 1094 | SP_1094_SOMERSAULT | somersault |
| 1095 | SP_1095_STRAIGHT | straight |
| 1096 | SP_1096_EBURSTER | eburster |
| 1097 | SP_1097_BACKSPIN | backspin |
| 1098 | SP_1098_EORB | eorb |
| 1099 | SP_1099_SCREW | screw |
| 1100 | SP_1100_DOUBLEUPPER | doubleupper |
| 1101 | SP_1101_DRAGONSTRIKE | dragonstrike |
| 1102 | SP_1102_DOUBLEFIRE | doublefire |
| 1103 | SP_1103_TRIPLEFIRE | triplefire |
| 1104 | SP_1104_FAKE | fake |
| 1105 | SP_1105_AIRSTRIKE | airstrike |
| 1106 | SP_1106_EDRAIN | edrain |
| 1107 | SP_1107_OCTOPUS | octopus |
| 1108 | SP_1108_BACKSTEP | backstep |
| 1109 | SP_1109_SHOT | shot |
| 1110 | SP_1110_SHOCKWAVE | shockwave |
| 1111 | SP_1111_DEMOLITION | demolition |
| 1112 | SP_1112_MAGNUMSHOT | magnumshot |
| 1113 | SP_1113_WINDSHOT | windshot |
| 1114 | SP_1114_SNATCH | snatch |
| 1115 | SP_1115_FIREBURNER | fireburner |
| 1116 | SP_1116_COOLINGEFFECT | coolingeffect |
| 1117 | SP_1117_FIST | fist |
| 1118 | SP_1118_TIMELEAP | timeleap |
| 1119 | SP_1119_RAPIDFIRE | rapidfire |
| 1120 | SP_1120_HOMING | homing |
| 1121 | SP_1121_GHOSTWALK | ghostwalk |
| 1122 | SP_1122_GHOSTSTAND | ghoststand |
| 1123 | SP_1123_GHOSTJUMP | ghostjump |
| 1124 | SP_1124_GHOSTPRONESTAB | ghostproneStab |
| 1125 | SP_1125_GHOSTLADDER | ghostladder |
| 1126 | SP_1126_GHOSTROPE | ghostrope |
| 1127 | SP_1127_GHOSTFLY | ghostfly |
| 1128 | SP_1128_GHOSTSIT | ghostsit |
| 1129 | SP_1129_CANNON | cannon |
| 1130 | SP_1130_TORPEDO | torpedo |
| 1131 | SP_1131_DARKSIGHT | darksight |
| 1132 | SP_1132_BAMBOO | bamboo |
| 1133 | SP_1133_WAVE | wave |
| 1134 | SP_1134_BLADE | blade |
| 1135 | SP_1135_SOULBLOW | soulblow |
| 1136 | SP_1136_FIRESTRIKE | firestrike |
| 1137 | SP_1137_FIRECURTAIN | firecurtain |
| 1138 | SP_1138_STORMBREATH | stormbreath |
| 1139 | SP_1139_VAMPIRE | vampire |
| 1140 | SP_1140_ISLOT | islot |
| 1141 | SP_1141_VSLOT | vslot |
| 1142 | SP_1142_SFX | sfx |
| 1143 | SP_1143_AFTERIMAGE | afterImage |
| 1144 | SP_1144_WALK | walk |
| 1145 | SP_1145_ATTACK | attack |
| 1146 | SP_1146_ATTACKSPEED | attackSpeed |
| 1147 | SP_1147_ZIGZAG | zigzag |
| 1148 | SP_1148_HAIRSHADE | hairShade |
| 1149 | SP_1149_H4H5 | H4H5 |
| 1150 | SP_1150_WEEKLY | weekly |
| 1151 | SP_1151_SHIP | _ship |
| 1152 | SP_1152_BEAST | beast |
| 1153 | SP_1153_DRAGON | dragon |
| 1154 | SP_1154_ETC | etc |
| 1155 | SP_1155_GAMEOBJECT | GameObject |
| 1156 | SP_1156_CREATURE | Creature |
| 1157 | SP_1157_USER | User |
| 1158 | SP_1158_PATCHEREXE | Patcher.exe |
| 1159 | SP_1159_NEWPATCHEREXE | NewPatcher.exe |
| 1160 | SP_1160_NEWPATCHERCHK | NewPatcher.chk |
| 1161 | SP_1161_PATCHERINFO | Patcher.info |
| 1162 | SP_1162_MAPLESTORYCLASS | MapleStoryClass |
| 1163 | SP_1163_MAPLESTORY | MapleStory |
| 1164 | SP_1164_BROWSERCONTROLPANESAVEOBJECT_N | BrowserControlPane::SaveObject\n |
| 1165 | SP_1165_BROWSERCONTROLPANEGETMONIKER_N | BrowserControlPane::GetMoniker\n |
| 1166 | SP_1166_BROWSERCONTROLPANEGETCONTAINER_N | BrowserControlPane::GetContainer\n |
| 1167 | SP_1167_BROWSERCONTROLPANESHOWOBJECT_N | BrowserControlPane::ShowObject\n |
| 1168 | SP_1168_BROWSERCONTROLPANEONSHOWWINDOW_N | BrowserControlPane::OnShowWindow\n |
| 1169 | SP_1169_SAVEOBJECTBROWSERCONTROLPANEREQUESTNEWOBJECTLAYOUT_N | SAVEOBJECT\tBrowserControlPane::RequestNewObjectLayout\n |
| 1170 | SP_1170_BROWSERCONTROLPANEGETWINDOW_N | BrowserControlPane::GetWindow\n |
| 1171 | SP_1171_BROWSERCONTROLPANECONTEXTSENSITIVEHELP_N | BrowserControlPane::ContextSensitiveHelp\n |
| 1172 | SP_1172_BROWSERCONTROLPANECANINPLACEACTIVATE_N | BrowserControlPane::CanInPlaceActivate\n |
| 1173 | SP_1173_BROWSERCONTROLPANEONINPLACEACTIVATE_N | BrowserControlPane::OnInPlaceActivate\n |
| 1174 | SP_1174_BROWSERCONTROLPANEONUIACTIVATE_N | BrowserControlPane::OnUIActivate\n |
| 1175 | SP_1175_BROWSERCONTROLPANEGETWINDOWCONTEXT_N | BrowserControlPane::GetWindowContext\n |
| 1176 | SP_1176_BROWSERCONTROLPANESCROLL_N | BrowserControlPane::Scroll\n |
| 1177 | SP_1177_BROWSERCONTROLPANEONUIDEACTIVATE_N | BrowserControlPane::OnUIDeactivate\n |
| 1178 | SP_1178_BROWSERCONTROLPANEONINPLACEDEACTIVATE_N | BrowserControlPane::OnInPlaceDeactivate\n |
| 1179 | SP_1179_BROWSERCONTROLPANEDISCARDUNDOSTATE_N | BrowserControlPane::DiscardUndoState\n |
| 1180 | SP_1180_BROWSERCONTROLPANEDEACTIVATEANDUNDO_N | BrowserControlPane::DeactivateAndUndo\n |
| 1181 | SP_1181_BROWSERCONTROLPANEONPOSRECTCHANGE_N | BrowserControlPane::OnPosRectChange\n |
| 1182 | SP_1182_BROWSERCONTROLPANEGETIDSOFNAMES_N | BrowserControlPane::GetIDsOfNames\n |
| 1183 | SP_1183_BROWSERCONTROLPANEGETTYPEINFO_N | BrowserControlPane::GetTypeInfo\n |
| 1184 | SP_1184_BROWSERCONTROLPANEGETTYPEINFOCOUNT_N | BrowserControlPane::GetTypeInfoCount\n |
| 1185 | SP_1185_BROWSERCONTROLPANEINVOKE_DISPIDMEMBER__N | BrowserControlPane::Invoke, dispIdMember \n |
| 1186 | SP_1186_BROWSERCONTROLPANEDISPIDNEWWINDOW2_N | BrowserControlPane::DISPID_NEWWINDOW2\n |
| 1187 | SP_1187_BROWSERCONTROLPANEDISPIDNAVIGATECOMPLETE2_N | BrowserControlPane::DISPID_NAVIGATECOMPLETE2\n |
| 1188 | SP_1188_BROWSERCONTROLPANEDISPIDDOCUMENTCOMPLETE_N | BrowserControlPane::DISPID_DOCUMENTCOMPLETE\n |
| 1189 | SP_1189_BROWSERCONTROLPANEENABLEMODELESS_N | BrowserControlPane::EnableModeless\n |
| 1190 | SP_1190_BROWSERCONTROLPANEFILTERDATAOBJECT_N | BrowserControlPane::FilterDataObject\n |
| 1191 | SP_1191_BROWSERCONTROLPANEGETDROPTARGET_N | BrowserControlPane::GetDropTarget\n |
| 1192 | SP_1192_BROWSERCONTROLPANEGETEXTERNAL_N | BrowserControlPane::GetExternal\n |
| 1193 | SP_1193_BROWSERCONTROLPANEGETHOSTINFO_N | BrowserControlPane::GetHostInfo\n |
| 1194 | SP_1194_BROWSERCONTROLPANEGETOPTIONKEYPATH_N | BrowserControlPane::GetOptionKeyPath\n |
| 1195 | SP_1195_BROWSERCONTROLPANEHIDEUI_N | BrowserControlPane::HideUI\n |
| 1196 | SP_1196_BROWSERCONTROLPANEONDOCWINDOWACTIVATE_N | BrowserControlPane::OnDocWindowActivate\n |
| 1197 | SP_1197_BROWSERCONTROLPANEONFRAMEWINDOWACTIVATE_N | BrowserControlPane::OnFrameWindowActivate\n |
| 1198 | SP_1198_BROWSERCONTROLPANERESIZEBORDER_N | BrowserControlPane::ResizeBorder\n |
| 1199 | SP_1199_BROWSERCONTROLPANESHOWCONTEXTMENU_N | BrowserControlPane::ShowContextMenu\n |
| 1200 | SP_1200_BROWSERCONTROLPANESHOWUI_N | BrowserControlPane::ShowUI\n |
| 1201 | SP_1201_BROWSERCONTROLPANETRANSLATEACCELERATOR_N | BrowserControlPane::TranslateAccelerator\n |
| 1202 | SP_1202_BROWSERCONTROLPANETRANSLATEURL_N | BrowserControlPane::TranslateUrl\n |
| 1203 | SP_1203_BROWSERCONTROLPANEUPDATEUI_N | BrowserControlPane::UpdateUI\n |
| 1204 | SP_1204_BROWSERCONTROLPANESHOWHELP_N | BrowserControlPane::ShowHelp\n |
| 1205 | SP_1205_BROWSERCONTROLPANESHOWMESSAGE_N | BrowserControlPane::ShowMessage\n |
| 1206 | SP_1206_UI_CASHSHOPIMG_CSCHAR_BTBUYAVATAR | UI/CashShop.img/CSChar/BtBuyAvatar |
| 1207 | SP_1207_UI_CASHSHOPIMG_CSCHAR_BTDEFAULTAVATAR | UI/CashShop.img/CSChar/BtDefaultAvatar |
| 1208 | SP_1208_UI_CASHSHOPIMG_CSLOCKER_BTREBATE | UI/CashShop.img/CSLocker/BtRebate |
| 1209 | SP_1209_UI_CASHSHOPIMG_CSLOCKER_BTREBATE2 | UI/CashShop.img/CSLocker/BtRebate2 |
| 1210 | SP_1210_UI_CASHSHOPIMG_CSINVENTORY_BTEXEQUIP | UI/CashShop.img/CSInventory/BtExEquip |
| 1211 | SP_1211_UI_CASHSHOPIMG_CSINVENTORY_BTEXCONSUME | UI/CashShop.img/CSInventory/BtExConsume |
| 1212 | SP_1212_UI_CASHSHOPIMG_CSINVENTORY_BTEXINSTALL | UI/CashShop.img/CSInventory/BtExInstall |
| 1213 | SP_1213_UI_CASHSHOPIMG_CSINVENTORY_BTEXETC | UI/CashShop.img/CSInventory/BtExEtc |
| 1214 | SP_1214_UI_CASHSHOPIMG_CSINVENTORY_BTEXPET | UI/CashShop.img/CSInventory/BtExPet |
| 1215 | SP_1215_UI_CASHSHOPIMG_CSINVENTORY_BTEXTRUNK | UI/CashShop.img/CSInventory/BtExTrunk |
| 1216 | SP_1216_UI_CASHSHOPIMG_CSTAB_TAB_D | UI/CashShop.img/CSTab/Tab/%d |
| 1217 | SP_1217_UI_CASHSHOPIMG_CSLIST_BASE | UI/CashShop.img/CSList/Base |
| 1218 | SP_1218_UI_CASHSHOPIMG_CSLIST_ITEMICON | UI/CashShop.img/CSList/ItemIcon |
| 1219 | SP_1219_UI_CASHSHOPIMG_PICTUREPLATE_HOWTO | UI/CashShop.img/PicturePlate/HowTo |
| 1220 | SP_1220_UI_CASHSHOPIMG_PICTUREPLATE_HOWTOGIFT | UI/CashShop.img/PicturePlate/HowToGift |
| 1221 | SP_1221_UI_CASHSHOPIMG_PICTUREPLATE_NOITEM | UI/CashShop.img/PicturePlate/NoItem |
| 1222 | SP_1222_UI_CASHSHOPIMG_NEWITEM | UI/CashShop.img/NewItem |
| 1223 | SP_1223_UI_CASHSHOPIMG_CSLIST_BTBUY | UI/CashShop.img/CSList/BtBuy |
| 1224 | SP_1224_UI_CASHSHOPIMG_CSLIST_BTGIFT | UI/CashShop.img/CSList/BtGift |
| 1225 | SP_1225_UI_CASHSHOPIMG_CSLIST_BTRESERVE | UI/CashShop.img/CSList/BtReserve |
| 1226 | SP_1226_UI_CASHSHOPIMG_CSLIST_BTREMOVE | UI/CashShop.img/CSList/BtRemove |
| 1227 | SP_1227_UI_CASHSHOPIMG_CSSTATUS_BTCHARGE | UI/CashShop.img/CSStatus/BtCharge |
| 1228 | SP_1228_UI_CASHSHOPIMG_CSSTATUS_BTCHECK | UI/CashShop.img/CSStatus/BtCheck |
| 1229 | SP_1229_UI_CASHSHOPIMG_CSSTATUS_BTCOUPON | UI/CashShop.img/CSStatus/BtCoupon |
| 1230 | SP_1230_UI_CASHSHOPIMG_CSSTATUS_BTEXIT | UI/CashShop.img/CSStatus/BtExit |
| 1231 | SP_1231_TAB | Tab |
| 1232 | SP_1232_DLGNOTICE | DlgNotice |
| 1233 | SP_1233_BUYSHOPITEM | BuyShopItem |
| 1234 | SP_1234_DRAGEND | DragEnd |
| 1235 | SP_1235_INVITE | Invite |
| 1236 | SP_1236_SCROLLUP | ScrollUp |
| 1237 | SP_1237_CHARSELECT | CharSelect |
| 1238 | SP_1238_ROLLUP | RollUp |
| 1239 | SP_1239_ROLLDOWN | RollDown |
| 1240 | SP_1240_WORLDMAPOPEN | WorldmapOpen |
| 1241 | SP_1241_WORLDMAPCLOSE | WorldmapClose |
| 1242 | SP_1242_SELECTMAP | SelectMap |
| 1243 | SP_1243_GAMEIN | GameIn |
| 1244 | SP_1244_DROPITEM | DropItem |
| 1245 | SP_1245_PICKUPITEM | PickUpItem |
| 1246 | SP_1246_ENCHANTSUCCESS | EnchantSuccess |
| 1247 | SP_1247_ENCHANTFAILURE | EnchantFailure |
| 1248 | SP_1248_WHISTLE | Whistle |
| 1249 | SP_1249_SOUND_BGMUIIMG_SHOPBGM | Sound/BgmUI.img/ShopBgm |
| 1250 | SP_1250_SOUND_BGMUIIMG_NXLOGOMS | Sound/BgmUI.img/NxLogoMS |
| 1251 | SP_1251_SOUND_BGMUIIMG_WZLOGO | Sound/BgmUI.img/WzLogo |
| 1252 | SP_1252_SOUND_BGM04_ARABPIRATE | Sound/Bgm04/ArabPirate |
| 1253 | SP_1253_UI_BASICIMG_ITEMNO | UI/Basic.img/ItemNo |
| 1254 | SP_1254_UI_BASICIMG_LEVELNO | UI/Basic.img/LevelNo |
| 1255 | SP_1255_UI_BASICIMG_BTOK | UI/Basic.img/BtOK |
| 1256 | SP_1256_UI_BASICIMG_BTCANCEL | UI/Basic.img/BtCancel |
| 1257 | SP_1257_UI_BASICIMG_BTOK2 | UI/Basic.img/BtOK2 |
| 1258 | SP_1258_UI_BASICIMG_BTCANCEL2 | UI/Basic.img/BtCancel2 |
| 1259 | SP_1259_UI_BASICIMG_CHECKBOX_D | UI/Basic.img/CheckBox/%d |
| 1260 | SP_1260_UI_BASICIMG_BTCLAIM | UI/Basic.img/BtClaim |
| 1261 | SP_1261_UI_BASICIMG_BTMIN | UI/Basic.img/BtMin |
| 1262 | SP_1262_UI_BASICIMG_BTMAX | UI/Basic.img/BtMax |
| 1263 | SP_1263_UI_UIWINDOWIMG_MINIMAP_BTMAP | UI/UIWindow.img/MiniMap/BtMap |
| 1264 | SP_1264_UI_BASICIMG_HSCR | UI/Basic.img/HScr |
| 1265 | SP_1265_UI_BASICIMG_VSCR | UI/Basic.img/VScr |
| 1266 | SP_1266_UI_BASICIMG_SLIDER_THUMBNORMAL | UI/Basic.img/Slider/thumbNormal |
| 1267 | SP_1267_UI_BASICIMG_SLIDER_THUMBPRESSED | UI/Basic.img/Slider/thumbPressed |
| 1268 | SP_1268_UI_BASICIMG_SLIDER_THUMBDISABLED | UI/Basic.img/Slider/thumbDisabled |
| 1269 | SP_1269_UI_BASICIMG_SLIDER_THUMBMOUSEOVER | UI/Basic.img/Slider/thumbMouseOver |
| 1270 | SP_1270_UI_BASICIMG_SLIDER_LEFT | UI/Basic.img/Slider/left |
| 1271 | SP_1271_UI_BASICIMG_SLIDER_MID | UI/Basic.img/Slider/mid |
| 1272 | SP_1272_UI_BASICIMG_SLIDER_RIGHT | UI/Basic.img/Slider/right |
| 1273 | SP_1273_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_BUYAVATAR | String/ToolTipHelp.img/Shop/Button/BuyAvatar |
| 1274 | SP_1274_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_DEFAULTAVATAR | String/ToolTipHelp.img/Shop/Button/DefaultAvatar |
| 1275 | SP_1275_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_REBATE | String/ToolTipHelp.img/Shop/Button/Rebate |
| 1276 | SP_1276_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_EXEQUIP | String/ToolTipHelp.img/Shop/Button/ExEquip |
| 1277 | SP_1277_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_EXCONSUME | String/ToolTipHelp.img/Shop/Button/ExConsume |
| 1278 | SP_1278_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_EXINSTALL | String/ToolTipHelp.img/Shop/Button/ExInstall |
| 1279 | SP_1279_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_EXETC | String/ToolTipHelp.img/Shop/Button/ExEtc |
| 1280 | SP_1280_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_CHARGE | String/ToolTipHelp.img/Shop/Button/Charge |
| 1281 | SP_1281_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_CHECK | String/ToolTipHelp.img/Shop/Button/Check |
| 1282 | SP_1282_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_COUPON | String/ToolTipHelp.img/Shop/Button/Coupon |
| 1283 | SP_1283_UI_UIWINDOWIMG_COUPON_BACKGRND | UI/UIWindow.img/Coupon/backgrnd |
| 1284 | SP_1284_UI_UIWINDOWIMG_FADEYESNO_BTOK | UI/UIWindow.img/FadeYesNo/BtOK |
| 1285 | SP_1285_UI_UIWINDOWIMG_FADEYESNO_BTCANCEL | UI/UIWindow.img/FadeYesNo/BtCancel |
| 1286 | SP_1286_UI_UIWINDOWIMG_FADEYESNO_ICON0 | UI/UIWindow.img/FadeYesNo/icon0 |
| 1287 | SP_1287_UI_UIWINDOWIMG_FADEYESNO_ICON1 | UI/UIWindow.img/FadeYesNo/icon1 |
| 1288 | SP_1288_UI_UIWINDOWIMG_FADEYESNO_ICON2 | UI/UIWindow.img/FadeYesNo/icon2 |
| 1289 | SP_1289_UI_UIWINDOWIMG_FADEYESNO_ICON3 | UI/UIWindow.img/FadeYesNo/icon3 |
| 1290 | SP_1290_UI_UIWINDOWIMG_FADEYESNO_ICON4 | UI/UIWindow.img/FadeYesNo/icon4 |
| 1291 | SP_1291_UI_UIWINDOWIMG_FADEYESNO_ICON5 | UI/UIWindow.img/FadeYesNo/icon5 |
| 1292 | SP_1292_UI_UIWINDOWIMG_FADEYESNO_ICON6 | UI/UIWindow.img/FadeYesNo/icon6 |
| 1293 | SP_1293_UI_UIWINDOWIMG_FADEYESNO_ICON7 | UI/UIWindow.img/FadeYesNo/icon7 |
| 1294 | SP_1294_UI_UIWINDOWIMG_FADEYESNO_ICON8 | UI/UIWindow.img/FadeYesNo/icon8 |
| 1295 | SP_1295_UI_UIWINDOWIMG_FADEYESNO_BACKGRND | UI/UIWindow.img/FadeYesNo/backgrnd |
| 1296 | SP_1296_UI_UIWINDOWIMG_FADEYESNO_BACKGRND2 | UI/UIWindow.img/FadeYesNo/backgrnd2 |
| 1297 | SP_1297_UI_UIWINDOWIMG_FADEYESNO_BACKGRND3 | UI/UIWindow.img/FadeYesNo/backgrnd3 |
| 1298 | SP_1298_UI_UIWINDOWIMG_FADEYESNO_BACKGRND4 | UI/UIWindow.img/FadeYesNo/backgrnd4 |
| 1299 | SP_1299_UI_UIWINDOWIMG_FADEYESNO_BACKGRND5 | UI/UIWindow.img/FadeYesNo/backgrnd5 |
| 1300 | SP_1300_UI_UIWINDOWIMG_FLOATNOTICE_D | UI/UIWindow.img/FloatNotice/%d |
| 1301 | SP_1301_UI_CASHSHOPIMG_BASE_BACKGRND | UI/CashShop.img/Base/backgrnd |
| 1302 | SP_1302_UI_CASHSHOPIMG_BASE_BACKGRND1 | UI/CashShop.img/Base/backgrnd1 |
| 1303 | SP_1303_UI_NAMETAG_D | UI/NameTag/%d |
| 1304 | SP_1304_UI_NAMETAGIMG_PET_D | UI/NameTag.img/pet/%d |
| 1305 | SP_1305_UI_NAMETAGIMG_MEDAL_D | UI/NameTag.img/medal/%d |
| 1306 | SP_1306_UI_MAPLOGINIMG | UI/MapLogin.img |
| 1307 | SP_1307_UI_LOGINIMG_COMMON_FRAME | UI/Login.img/Common/frame |
| 1308 | SP_1308_UI_LOGINIMG_COMMON_SELECTWORLD | UI/Login.img/Common/selectWorld |
| 1309 | SP_1309_UI_LOGINIMG_COMMON_STEP_D | UI/Login.img/Common/step/%d |
| 1310 | SP_1310_UI_LOGINIMG_COMMON_BTSTART | UI/Login.img/Common/BtStart |
| 1311 | SP_1311_UI_LOGINIMG_COMMON_BTNOTICE | UI/Login.img/Common/BtNotice |
| 1312 | SP_1312_UI_LOGINIMG_COMMON_BTEXIT | UI/Login.img/Common/BtExit |
| 1313 | SP_1313_UI_LOGINIMG_CHARSELECT_EFFECT_0 | UI/Login.img/CharSelect/effect/0 |
| 1314 | SP_1314_UI_LOGINIMG_CHARSELECT_EFFECT_1 | UI/Login.img/CharSelect/effect/1 |
| 1315 | SP_1315_UI_LOGINIMG_CHARSELECT_PAGEL_ | UI/Login.img/CharSelect/pageL/ |
| 1316 | SP_1316_UI_LOGINIMG_CHARSELECT_PAGER_ | UI/Login.img/CharSelect/pageR/ |
| 1317 | SP_1317_UI_LOGINIMG_CHARSELECT_CHARACTER_0 | UI/Login.img/CharSelect/character/0 |
| 1318 | SP_1318_UI_LOGINIMG_CHARSELECT_CHARACTER_1 | UI/Login.img/CharSelect/character/1 |
| 1319 | SP_1319_UI_LOGINIMG_CHARSELECT_BTSELECT | UI/Login.img/CharSelect/BtSelect |
| 1320 | SP_1320_UI_LOGINIMG_CHARSELECT_BTNEW | UI/Login.img/CharSelect/BtNew |
| 1321 | SP_1321_UI_LOGINIMG_CHARSELECT_BTDELETE | UI/Login.img/CharSelect/BtDelete |
| 1322 | SP_1322_UI_LOGINIMG_CHARSELECT_CHARINFO | UI/Login.img/CharSelect/charInfo |
| 1323 | SP_1323_UI_LOGINIMG_CHARSELECT_NAMETAG | UI/Login.img/CharSelect/nameTag |
| 1324 | SP_1324_UI_LOGINIMG_CHARSELECT_KNIGHT | UI/Login.img/CharSelect/knight |
| 1325 | SP_1325_UI_LOGINIMG_WORLDNOTICE_POPULATED_D | UI/Login.img/WorldNotice/Populated/%d |
| 1326 | SP_1326_UI_LOGINIMG_NOTICE_BACKGRND_0 | UI/Login.img/Notice/backgrnd/0 |
| 1327 | SP_1327_UI_LOGINIMG_NOTICE_BACKGRND_1 | UI/Login.img/Notice/backgrnd/1 |
| 1328 | SP_1328_UI_LOGINIMG_NOTICE_BACKGRND_2 | UI/Login.img/Notice/backgrnd/2 |
| 1329 | SP_1329_UI_LOGINIMG_NOTICE_BTYES | UI/Login.img/Notice/BtYes |
| 1330 | SP_1330_UI_LOGINIMG_NOTICE_BTNO | UI/Login.img/Notice/BtNo |
| 1331 | SP_1331_UI_LOGINIMG_NOTICESCROLL1 | UI/Login.img/NoticeScroll1 |
| 1332 | SP_1332_UI_LOGINIMG_NOTICESCROLL2 | UI/Login.img/NoticeScroll2 |
| 1333 | SP_1333_UI_LOGINIMG_NOTICE_TEXT_D | UI/Login.img/Notice/text/%d |
| 1334 | SP_1334_UI_LOGINIMG_NEWCHAR_CHARNAME | UI/Login.img/NewChar/charName |
| 1335 | SP_1335_UI_LOGINIMG_NEWCHAR_CHARSET | UI/Login.img/NewChar/charSet |
| 1336 | SP_1336_UI_LOGINIMG_NEWCHAR_AVATARSEL | UI/Login.img/NewChar/avatarSel |
| 1337 | SP_1337_UI_LOGINIMG_NEWCHAR_BTLEFT | UI/Login.img/NewChar/BtLeft |
| 1338 | SP_1338_UI_LOGINIMG_NEWCHAR_BTRIGHT | UI/Login.img/NewChar/BtRight |
| 1339 | SP_1339_UI_LOGINIMG_NEWCHAR_SCROLL_0 | UI/Login.img/NewChar/scroll/0 |
| 1340 | SP_1340_UI_LOGINIMG_NEWCHAR_BTCHECK | UI/Login.img/NewChar/BtCheck |
| 1341 | SP_1341_UI_LOGINIMG_NEWCHAR_BTYES | UI/Login.img/NewChar/BtYes |
| 1342 | SP_1342_UI_LOGINIMG_NEWCHAR_BTNO | UI/Login.img/NewChar/BtNo |
| 1343 | SP_1343_UI_LOGINIMG_NEWCHAR_DICE | UI/Login.img/NewChar/dice |
| 1344 | SP_1344_UI_LOGINIMG_NEWCHAR_STATTB | UI/Login.img/NewChar/statTb |
| 1345 | SP_1345_UI_LOGINIMG_TITLE_BTLOGIN | UI/Login.img/Title/BtLogin |
| 1346 | SP_1346_UI_LOGINIMG_TITLE_BTGUESTLOGIN | UI/Login.img/Title/BtGuestLogin |
| 1347 | SP_1347_UI_LOGINIMG_TITLE_BTLOGINIDSAVE | UI/Login.img/Title/BtLoginIDSave |
| 1348 | SP_1348_UI_LOGINIMG_TITLE_BTLOGINIDLOST | UI/Login.img/Title/BtLoginIDLost |
| 1349 | SP_1349_UI_LOGINIMG_TITLE_BTPASSWDLOST | UI/Login.img/Title/BtPasswdLost |
| 1350 | SP_1350_UI_LOGINIMG_TITLE_BTNEW | UI/Login.img/Title/BtNew |
| 1351 | SP_1351_UI_LOGINIMG_TITLE_BTHOMEPAGE | UI/Login.img/Title/BtHomePage |
| 1352 | SP_1352_UI_LOGINIMG_TITLE_BTQUIT | UI/Login.img/Title/BtQuit |
| 1353 | SP_1353_UI_LOGINIMG_TITLE_CHECK_0 | UI/Login.img/Title/check/0 |
| 1354 | SP_1354_UI_LOGINIMG_TITLE_CHECK_1 | UI/Login.img/Title/check/1 |
| 1355 | SP_1355_UI_LOGINIMG_WORLDSELECT_BTWORLD_D | UI/Login.img/WorldSelect/BtWorld/%d |
| 1356 | SP_1356_UI_LOGINIMG_WORLDSELECT_BTWORLD_TD | UI/Login.img/WorldSelect/BtWorld/t%d |
| 1357 | SP_1357_UI_LOGINIMG_WORLDSELECT_BTWORLD_E | UI/Login.img/WorldSelect/BtWorld/e |
| 1358 | SP_1358_UI_LOGINIMG_WORLDSELECT_CHBACKGRN | UI/Login.img/WorldSelect/chBackgrn |
| 1359 | SP_1359_UI_LOGINIMG_WORLDSELECT_CHANNEL_CHGAUGE | UI/Login.img/WorldSelect/channel/chgauge |
| 1360 | SP_1360_UI_LOGINIMG_WORLDSELECT_BTGOWORLD | UI/Login.img/WorldSelect/BtGoworld |
| 1361 | SP_1361_WORLDSELECT | WorldSelect |
| 1362 | SP_1362_UI_LOGINIMG_WORLDSELECT_CHANNEL_D_NORMAL | UI/Login.img/WorldSelect/channel/%d/normal |
| 1363 | SP_1363_UI_LOGINIMG_WORLDSELECT_CHANNEL_D_DISABLED | UI/Login.img/WorldSelect/channel/%d/disabled |
| 1364 | SP_1364_UI_LOGINIMG_WORLDSELECT_SCROLL_0 | UI/Login.img/WorldSelect/scroll/0 |
| 1365 | SP_1365_UI_LOGINIMG_WORLDSELECT_SCROLL_1 | UI/Login.img/WorldSelect/scroll/1 |
| 1366 | SP_1366_UI_LOGINIMG_WORLDSELECT_WORLD_D | UI/Login.img/WorldSelect/world/%d |
| 1367 | SP_1367_UI_LOGINIMG_WORLDSELECT_WORLD_TD | UI/Login.img/WorldSelect/world/t%d |
| 1368 | SP_1368_UI_LOGINIMG_WORLDSELECT_CHANNEL_CHSELECT | UI/Login.img/WorldSelect/channel/chSelect |
| 1369 | SP_1369_SINCE_THERE_ARE_MANY_R_NCONCURRENT_USERS_IN_THIS_WORLD_R_NYOU_MAY_ENCOUNTER_SOME | Since There Are Many\r\nConcurrent Users in This World,\r\nYou May Encounter Some Difficulties During the Game Play. |
| 1370 | SP_1370_THE_CONCURRENT_USERS_IN_THIS_WORLD_R_NHAVE_REACHED_THE_MAX_R_NPLEASE_TRY_AGAIN_L | The Concurrent Users in This World\r\nHave Reached the Max.\r\nPlease Try Again Later. |
| 1371 | SP_1371_UI_LOGINIMG_NOTICE_LOADING_BACKGRND | UI/Login.img/Notice/Loading/backgrnd |
| 1372 | SP_1372_UI_LOGINIMG_NOTICE_LOADING_BTCANCEL | UI/Login.img/Notice/Loading/BtCancel |
| 1373 | SP_1373_UI_LOGINIMG_NOTICE_LOADING_BAR | UI/Login.img/Notice/Loading/bar |
| 1374 | SP_1374_UI_LOGINIMG_NOTICE_LOADING_BAR_D | UI/Login.img/Notice/Loading/bar/%d |
| 1375 | SP_1375_UI_GATEWAYIMG_WORLDSELECT | UI/Gateway.img/WorldSelect |
| 1376 | SP_1376_LOGO | logo |
| 1377 | SP_1377_UI_GATEWAYIMG_WORLDSELECT_ICON | UI/Gateway.img/WorldSelect/icon |
| 1378 | SP_1378_UI_GATEWAYIMG_WORLDSELECT_TOOLTIP | UI/Gateway.img/WorldSelect/tooltip |
| 1379 | SP_1379_UI_GATEWAYIMG_WORLDSELECT_STEP_0 | UI/Gateway.img/WorldSelect/step/0 |
| 1380 | SP_1380_UI_GATEWAYIMG_WORLDSELECT_SHADOW | UI/Gateway.img/WorldSelect/shadow |
| 1381 | SP_1381_UI_GATEWAYIMG_WORLDSELECT_BTRETURN | UI/Gateway.img/WorldSelect/BtReturn |
| 1382 | SP_1382_UI_LOGINIMG_COMMON_STEP_5 | UI/Login.img/Common/step/5 |
| 1383 | SP_1383_UI_LOGOIMG_WIZET | UI/Logo.img/Wizet |
| 1384 | SP_1384_UI_LOGOIMG_NEXON | UI/Logo.img/Nexon |
| 1385 | SP_1385_ETC_CATEGORYIMG | Etc/Category.img |
| 1386 | SP_1386_CATEGORYSUB | CategorySub |
| 1387 | SP_1387_NAME | Name |
| 1388 | SP_1388_UI_CHATBALLOONIMG_ | UI/ChatBalloon.img/ |
| 1389 | SP_1389_NPC | npc |
| 1390 | SP_1390_ADBOARD | adboard |
| 1391 | SP_1391_ENTER_A_NOTE | Enter a note. |
| 1392 | SP_1392_THE_CHALKBOARD_HAS_CLOSED | The chalkboard has closed. |
| 1393 | SP_1393_YOU_CANNOT_USE_THE_CHALKBOARD_HERE | You cannot use the chalkboard here. |
| 1394 | SP_1394_NW | nw |
| 1395 | SP_1395_N | n |
| 1396 | SP_1396_NE | ne |
| 1397 | SP_1397_E | e |
| 1398 | SP_1398_W | w |
| 1399 | SP_1399_C | c |
| 1400 | SP_1400_SW | sw |
| 1401 | SP_1401_S | s |
| 1402 | SP_1402_SE | se |
| 1403 | SP_1403_ARROW | arrow |
| 1404 | SP_1404_SLARROW | slArrow |
| 1405 | SP_1405_SELARROW | selArrow |
| 1406 | SP_1406_SWLARROW | swlArrow |
| 1407 | SP_1407_NELARROW | nelArrow |
| 1408 | SP_1408_NWLARROW | nwlArrow |
| 1409 | SP_1409_CLR | clr |
| 1410 | SP_1410_CANVASFONT | Canvas#Font |
| 1411 | SP_1411_BA | BA |
| 1412 | SP_1412_B | B |
| 1413 | SP_1413_S | S |
| 1414 | SP_1414_UI_CHATBALLOONIMG_MINIROOM | UI/ChatBalloon.img/miniroom |
| 1415 | SP_1415_PERSONALSHOP | PersonalShop |
| 1416 | SP_1416_OMOK | Omok |
| 1417 | SP_1417_BACKGRND2 | backgrnd2 |
| 1418 | SP_1418_MEMORYGAME_D | MemoryGame/%d |
| 1419 | SP_1419_LOCK | Lock |
| 1420 | SP_1420_UNLOCK | Unlock |
| 1421 | SP_1421_CNUM | cNum |
| 1422 | SP_1422_MNUM | mNum |
| 1423 | SP_1423_ABLE | Able |
| 1424 | SP_1424_DISABLE | Disable |
| 1425 | SP_1425_PROGRESS | Progress |
| 1426 | SP_1426_ETC_CURSEIMG | Etc/Curse.img |
| 1427 | SP_1427_ETC_SWINDLEIMG_ | Etc/Swindle.img/ |
| 1428 | SP_1428_ETC_SWINDLEIMG_D_WORD | Etc/Swindle.img/%d/word |
| 1429 | SP_1429_ETC_SWINDLEIMG_D_WARN | Etc/Swindle.img/%d/warn |
| 1430 | SP_1430_COMMA | comma |
| 1431 | SP_1431_AM | AM |
| 1432 | SP_1432_PM | PM |
| 1433 | SP_1433_NORMAL | normal |
| 1434 | SP_1434__NORMAL_0 | /normal/0 |
| 1435 | SP_1435_PRESSED | pressed |
| 1436 | SP_1436__PRESSED_0 | /pressed/0 |
| 1437 | SP_1437_DISABLED | disabled |
| 1438 | SP_1438__DISABLED_0 | /disabled/0 |
| 1439 | SP_1439_MOUSEOVER | mouseOver |
| 1440 | SP_1440__MOUSEOVER_0 | /mouseOver/0 |
| 1441 | SP_1441_KEYFOCUSED | keyFocused |
| 1442 | SP_1442__KEYFOCUSED_0 | /keyFocused/0 |
| 1443 | SP_1443_BTMOUSECLICK | BtMouseClick |
| 1444 | SP_1444_BTMOUSEOVER | BtMouseOver |
| 1445 | SP_1445_TITLE | title |
| 1446 | SP_1446_DESC | Desc |
| 1447 | SP_1447_DESC | desc |
| 1448 | SP_1448_MIN | Min |
| 1449 | SP_1449_MIN | min |
| 1450 | SP_1450_MAX | max |
| 1451 | SP_1451__ENABLED_PREV0 | /enabled/prev0 |
| 1452 | SP_1452__ENABLED_PREV1 | /enabled/prev1 |
| 1453 | SP_1453__ENABLED_NEXT0 | /enabled/next0 |
| 1454 | SP_1454__ENABLED_NEXT1 | /enabled/next1 |
| 1455 | SP_1455__ENABLED_BASE | /enabled/base |
| 1456 | SP_1456__ENABLED_THUMB0 | /enabled/thumb0 |
| 1457 | SP_1457__ENABLED_THUMB1 | /enabled/thumb1 |
| 1458 | SP_1458__DISABLED_PREV | /disabled/prev |
| 1459 | SP_1459__DISABLED_NEXT | /disabled/next |
| 1460 | SP_1460__DISABLED_BASE | /disabled/base |
| 1461 | SP_1461_SHIPOBJ | shipObj |
| 1462 | SP_1462_SHIPKIND | shipKind |
| 1463 | SP_1463_TMOVE | tMove |
| 1464 | SP_1464_F | f |
| 1465 | SP_1465_X0 | x0 |
| 1466 | SP_1466_SNOWBALL | snowBall |
| 1467 | SP_1467_DX | dx |
| 1468 | SP_1468_DY | dy |
| 1469 | SP_1469_DX1 | dx1 |
| 1470 | SP_1470_DY1 | dy1 |
| 1471 | SP_1471_XMIN | xMin |
| 1472 | SP_1472_XMAX | xMax |
| 1473 | SP_1473_PORTAL | portal |
| 1474 | SP_1474_SNOWMAN | snowMan |
| 1475 | SP_1475_FLY | Fly |
| 1476 | SP_1476_ETC_MAKECHARINFOIMG_CHARMALE | Etc/MakeCharInfo.img/CharMale |
| 1477 | SP_1477_ETC_MAKECHARINFOIMG_CHARFEMALE | Etc/MakeCharInfo.img/CharFemale |
| 1478 | SP_1478_ETC_MAKECHARINFOIMG_PREMIUMCHARMALE | Etc/MakeCharInfo.img/PremiumCharMale |
| 1479 | SP_1479_ETC_MAKECHARINFOIMG_PREMIUMCHARFEMALE | Etc/MakeCharInfo.img/PremiumCharFemale |
| 1480 | SP_1480_ETC_MAKECHARINFOIMG_INFO_CHARMALE | Etc/MakeCharInfo.img/Info/CharMale |
| 1481 | SP_1481_ETC_MAKECHARINFOIMG_INFO_CHARFEMALE | Etc/MakeCharInfo.img/Info/CharFemale |
| 1482 | SP_1482_ETC_MAKECHARINFOIMG_NAME_CHARMALE_D_D | Etc/MakeCharInfo.img/Name/CharMale/%d/%d |
| 1483 | SP_1483_ETC_MAKECHARINFOIMG_NAME_CHARFEMALE_D_D | Etc/MakeCharInfo.img/Name/CharFemale/%d/%d |
| 1484 | SP_1484_ETC_MAKECHARINFOIMG_NAME_PREMIUMCHARMALE_D_D | Etc/MakeCharInfo.img/Name/PremiumCharMale/%d/%d |
| 1485 | SP_1485_ETC_MAKECHARINFOIMG_NAME_PREMIUMCHARFEMALE_D_D | Etc/MakeCharInfo.img/Name/PremiumCharFemale/%d/%d |
| 1486 | SP_1486_FOOTHOLD | foothold |
| 1487 | SP_1487_LADDERROPE | ladderRope |
| 1488 | SP_1488_TILE | tile |
| 1489 | SP_1489_MAP_TILE_ | Map/Tile/ |
| 1490 | SP_1490_TS | tS |
| 1491 | SP_1491_U | u |
| 1492 | SP_1492_NO | no |
| 1493 | SP_1493_ZM | zM |
| 1494 | SP_1494_OBJ | obj |
| 1495 | SP_1495_MAP_OBJ_SIMG_S_S_S | Map/Obj/%s.img/%s/%s/%s |
| 1496 | SP_1496_OS | oS |
| 1497 | SP_1497_L0 | l0 |
| 1498 | SP_1498_L1 | l1 |
| 1499 | SP_1499_L2 | l2 |
| 1500 | SP_1500_BLEND | blend |
| 1501 | SP_1501_ONCE | once |
| 1502 | SP_1502_OBSTACLE | obstacle |
| 1503 | SP_1503_DAMAGE | damage |
| 1504 | SP_1504_MOBDAMAGE | mobdamage |
| 1505 | SP_1505_DIR | dir |
| 1506 | SP_1506_BACK | back |
| 1507 | SP_1507_BS | bS |
| 1508 | SP_1508_MAP_BACK_ | Map/Back/ |
| 1509 | SP_1509_RX | rx |
| 1510 | SP_1510_RY | ry |
| 1511 | SP_1511_CX | cx |
| 1512 | SP_1512_CY | cy |
| 1513 | SP_1513_ANI | ani |
| 1514 | SP_1514_A | a |
| 1515 | SP_1515_TYPE | type |
| 1516 | SP_1516_FRONT | front |
| 1517 | SP_1517_BACKD | back%d |
| 1518 | SP_1518_BACK_D | back/%d |
| 1519 | SP_1519_ANI_D | ani/%d |
| 1520 | SP_1520_MOVETYPE | moveType |
| 1521 | SP_1521_MOVER | moveR |
| 1522 | SP_1522_MOVEW | moveW |
| 1523 | SP_1523_MOVEH | moveH |
| 1524 | SP_1524_MOVEP | moveP |
| 1525 | SP_1525_CLOUD | cloud |
| 1526 | SP_1526_MAP_MAPHELPERIMG_WEATHER_CLOUD | Map/MapHelper.img/weather/cloud |
| 1527 | SP_1527_NORESIZE | noresize |
| 1528 | SP_1528_VRLEFT | VRLeft |
| 1529 | SP_1529_VRRIGHT | VRRight |
| 1530 | SP_1530_VRTOP | VRTop |
| 1531 | SP_1531_VRBOTTOM | VRBottom |
| 1532 | SP_1532_VRLIMIT | VRLimit |
| 1533 | SP_1533_BGM | bgm |
| 1534 | SP_1534_SOUND_ | Sound/ |
| 1535 | SP_1535_UI_UIWINDOWIMG_MINIGAME_OMOK_BACKGRND | UI/UIWindow.img/Minigame/Omok/backgrnd |
| 1536 | SP_1536_UI_UIWINDOWIMG_MINIGAME_MEMORYGAME_BACKGRND | UI/UIWindow.img/Minigame/MemoryGame/backgrnd |
| 1537 | SP_1537_UI_UIWINDOWIMG_MINIGAME_COMMON | UI/UIWindow.img/Minigame/Common |
| 1538 | SP_1538_UI_UIWINDOWIMG_MINIGAME_MEMORYGAME_CARD | UI/UIWindow.img/Minigame/MemoryGame/card |
| 1539 | SP_1539_UI_UIWINDOWIMG_MINIGAME_MEMORYGAME_NUMBER | UI/UIWindow.img/Minigame/MemoryGame/number |
| 1540 | SP_1540_UI_UIWINDOWIMG_MINIGAME_OMOK_STONE_D | UI/UIWindow.img/Minigame/Omok/stone/%d |
| 1541 | SP_1541_UI_UIWINDOWIMG_PERSONALSHOP_BACKGRND | UI/UIWindow.img/PersonalShop/backgrnd |
| 1542 | SP_1542_UI_UIWINDOWIMG_SHOP_SELECT | UI/UIWindow.img/Shop/select |
| 1543 | SP_1543_UI_UIWINDOWIMG_SHOP_MESO | UI/UIWindow.img/Shop/meso |
| 1544 | SP_1544_UI_UIWINDOWIMG_SHOP_TOKEN | UI/UIWindow.img/Shop/token |
| 1545 | SP_1545_UI_UIWINDOWIMG_PERSONALSHOP_BTSTART | UI/UIWindow.img/PersonalShop/BtStart |
| 1546 | SP_1546_UI_UIWINDOWIMG_PERSONALSHOP_BTBAN | UI/UIWindow.img/PersonalShop/BtBan |
| 1547 | SP_1547_UI_UIWINDOWIMG_SHOP_BTBUY | UI/UIWindow.img/Shop/BtBuy |
| 1548 | SP_1548_UI_UIWINDOWIMG_SHOP_BTSELL | UI/UIWindow.img/Shop/BtSell |
| 1549 | SP_1549_UI_UIWINDOWIMG_BTUICLOSE2 | UI/UIWindow.img/BtUIClose2 |
| 1550 | SP_1550_UI_UIWINDOWIMG_SHOP_BTRECHARGE | UI/UIWindow.img/Shop/BtRecharge |
| 1551 | SP_1551_UI_UIWINDOWIMG_SHOP_BACKGRND | UI/UIWindow.img/Shop/backgrnd |
| 1552 | SP_1552_UI_UIWINDOWIMG_PERSONALSHOP_BACKGRND2 | UI/UIWindow.img/PersonalShop/backgrnd2 |
| 1553 | SP_1553_BARBAGIC | barBagic |
| 1554 | SP_1554_MARK | mark |
| 1555 | SP_1555_INFO2 | info2 |
| 1556 | SP_1556_INFO3 | info3 |
| 1557 | SP_1557_ENTER | Enter |
| 1558 | SP_1558_MATCH | Match |
| 1559 | SP_1559_NOMATCH | NoMatch |
| 1560 | SP_1560_DRAW | Draw |
| 1561 | SP_1561_WIN | Win |
| 1562 | SP_1562_LOOSE | Loose |
| 1563 | SP_1563_TIMER | Timer |
| 1564 | SP_1564_READY | Ready |
| 1565 | SP_1565_OPEN | Open |
| 1566 | SP_1566_MUSHROOM | Mushroom |
| 1567 | SP_1567_SLIME | Slime |
| 1568 | SP_1568_REEL | Reel |
| 1569 | SP_1569_UI_UIWINDOWIMG_MINIGAME_COMMON_BTSTART | UI/UIWindow.img/Minigame/Common/btStart |
| 1570 | SP_1570_UI_UIWINDOWIMG_MINIGAME_COMMON_BTREADY | UI/UIWindow.img/Minigame/Common/btReady |
| 1571 | SP_1571_UI_UIWINDOWIMG_MINIGAME_COMMON_BTDRAW | UI/UIWindow.img/Minigame/Common/btDraw |
| 1572 | SP_1572_UI_UIWINDOWIMG_MINIGAME_COMMON_BTABSTEN | UI/UIWindow.img/Minigame/Common/btAbsten |
| 1573 | SP_1573_UI_UIWINDOWIMG_MINIGAME_COMMON_BTBAN | UI/UIWindow.img/Minigame/Common/btBan |
| 1574 | SP_1574_UI_UIWINDOWIMG_MINIGAME_COMMON_BTEXIT | UI/UIWindow.img/Minigame/Common/btExit |
| 1575 | SP_1575_UI_UIWINDOWIMG_MINIGAME_COMMON_BTREFUND | UI/UIWindow.img/Minigame/Common/btRefund |
| 1576 | SP_1576_TURN | turn |
| 1577 | SP_1577_UI_UIWINDOWIMG_MINIGAME_MEMORYGAME_EFFECTD | UI/UIWindow.img/Minigame/MemoryGame/effect%d |
| 1578 | SP_1578_CHARLOSE | charLose |
| 1579 | SP_1579_CHARWIN | charWin |
| 1580 | SP_1580_WIN | win |
| 1581 | SP_1581_DRAW | draw |
| 1582 | SP_1582_LOSE | lose |
| 1583 | SP_1583_READYON | readyOn |
| 1584 | SP_1584_READYOFF | readyOff |
| 1585 | SP_1585_UI_UIWINDOWIMG_MULTILINE_BACKGRND | UI/UIWindow.img/MultiLine/backgrnd |
| 1586 | SP_1586_UI_UIWINDOWIMG_MULTILINE_BTCLOSE | UI/UIWindow.img/MultiLine/BtClose |
| 1587 | SP_1587_MSG | msg |
| 1588 | SP_1588_ICONREWARD | iconReward |
| 1589 | SP_1589_ICONRAW | iconRaw |
| 1590 | SP_1590_EFFECT_SUMMONIMG_D | Effect/Summon.img/%d |
| 1591 | SP_1591_SOUND_SKILLIMG_07D_HIT | Sound/Skill.img/%07d/Hit |
| 1592 | SP_1592__HITAFTER | /hitAfter |
| 1593 | SP_1593_MOB | mob |
| 1594 | SP_1594_MOB_07DIMG_STAND_0 | Mob/%07d.img/stand/0 |
| 1595 | SP_1595_MOBHP | mobHP |
| 1596 | SP_1596_BRIDLEMSGTYPE | bridleMsgType |
| 1597 | SP_1597_BRIDLEPROP | bridleProp |
| 1598 | SP_1598_BRIDLEPROPCHG | bridlePropChg |
| 1599 | SP_1599_USEDELAY | useDelay |
| 1600 | SP_1600_DELAYMSG | delayMsg |
| 1601 | SP_1601_NOMOBMSG | nomobMsg |
| 1602 | SP_1602_MOB_ | Mob/ |
| 1603 | SP_1603_MOB_SIMG | Mob/%s.img |
| 1604 | SP_1604_NAME | name |
| 1605 | SP_1605_FUNC | func |
| 1606 | SP_1606_SPECIES | species |
| 1607 | SP_1607_BODYATTACK | bodyAttack |
| 1608 | SP_1608_BOSS | boss |
| 1609 | SP_1609_HIDEHP | hideHP |
| 1610 | SP_1610_HIDELEVEL | hideLevel |
| 1611 | SP_1611_LEVEL | level |
| 1612 | SP_1612_MAXHP | maxHP |
| 1613 | SP_1613_MAXMP | maxMP |
| 1614 | SP_1614_SPEED | speed |
| 1615 | SP_1615_FLYSPEED | flySpeed |
| 1616 | SP_1616_CHASESPEED | chaseSpeed |
| 1617 | SP_1617_PADAMAGE | PADamage |
| 1618 | SP_1618_PDDAMAGE | PDDamage |
| 1619 | SP_1619_MADAMAGE | MADamage |
| 1620 | SP_1620_MDDAMAGE | MDDamage |
| 1621 | SP_1621_ACC | acc |
| 1622 | SP_1622_EVA | eva |
| 1623 | SP_1623_EXP | exp |
| 1624 | SP_1624_PUSHED | pushed |
| 1625 | SP_1625_ELEMATTR | elemAttr |
| 1626 | SP_1626_HPRECOVERY | hpRecovery |
| 1627 | SP_1627_MPRECOVERY | mpRecovery |
| 1628 | SP_1628_HPGAUGEHIDE | HPgaugeHide |
| 1629 | SP_1629_ANGERGAUGE | AngerGauge |
| 1630 | SP_1630_CHARGECOUNT | ChargeCount |
| 1631 | SP_1631_PICKUP | pickUp |
| 1632 | SP_1632_FIRSTATTACK | firstAttack |
| 1633 | SP_1633_FS | fs |
| 1634 | SP_1634_INACTIVE | inactive |
| 1635 | SP_1635_JUMPATTACK | jumpAttack |
| 1636 | SP_1636_RANGE | range |
| 1637 | SP_1637_MOBCOUNT | mobCount |
| 1638 | SP_1638_ATTACKCOUNT | attackCount |
| 1639 | SP_1639_BULLETCOUNT | bulletCount |
| 1640 | SP_1640_BULLETCONSUME | bulletConsume |
| 1641 | SP_1641_MASTERY | mastery |
| 1642 | SP_1642_SP | sp |
| 1643 | SP_1643_R | r |
| 1644 | SP_1644_EFFECT | effect |
| 1645 | SP_1645_SCREEN | screen |
| 1646 | SP_1646_KEYWORDEFFECT | keywordEffect |
| 1647 | SP_1647_KEYWORD | keyword |
| 1648 | SP_1648_MOB_07DIMG_S_INFO_EFFECT | Mob/%07d.img/%s/info/effect |
| 1649 | SP_1649_MOB_07DIMG_S_INFO_HIT | Mob/%07d.img/%s/info/hit |
| 1650 | SP_1650_MOB_07DIMG_S_INFO_BALL | Mob/%07d.img/%s/info/ball |
| 1651 | SP_1651_MOB_07DIMG_S_INFO_AREAWARNING | Mob/%07d.img/%s/info/areaWarning |
| 1652 | SP_1652_MOB_07DIMG_S_INFO_AREAATTACK | Mob/%07d.img/%s/info/areaAttack |
| 1653 | SP_1653_ATTACH | attach |
| 1654 | SP_1654_ATTACKAFTER | attackAfter |
| 1655 | SP_1655_EFFECTAFTER | effectAfter |
| 1656 | SP_1656_UI_UIWINDOWIMG_ITEMMOVE_BACKGRND | UI/UIWindow.img/ItemMove/backgrnd |
| 1657 | SP_1657_UI_UIWINDOWIMG_ITEMMOVE_BTMOVE | UI/UIWindow.img/ItemMove/BtMove |
| 1658 | SP_1658_UI_UIWINDOWIMG_ITEMMOVE_BTCANCEL | UI/UIWindow.img/ItemMove/BtCancel |
| 1659 | SP_1659_UI_UIWINDOWIMG_ITEMMOVE_BTCOIN | UI/UIWindow.img/ItemMove/BtCoin |
| 1660 | SP_1660_UI_UIWINDOWIMG_ITEMMOVE_BTCLOSE | UI/UIWindow.img/ItemMove/BtClose |
| 1661 | SP_1661_ | 굴림 |
| 1662 | SP_1662_ | 돋움 |
| 1663 | SP_1663_ | 바탕 |
| 1664 | SP_1664_ | 궁서 |
| 1665 | SP_1665_UI_BASICIMG_DCMARK | UI/Basic.img/dcMark |
| 1666 | SP_1666_STRING_NPCIMG | String/Npc.img |
| 1667 | SP_1667_NPC_ | Npc/ |
| 1668 | SP_1668_NPC_SIMG | Npc/%s.img |
| 1669 | SP_1669_DCMARK | dcMark |
| 1670 | SP_1670_SPEAK | speak |
| 1671 | SP_1671_DCLEFT | dcLeft |
| 1672 | SP_1672_DCTOP | dcTop |
| 1673 | SP_1673_DCRIGHT | dcRight |
| 1674 | SP_1674_DCBOTTOM | dcBottom |
| 1675 | SP_1675_TALKMOUSEONLY | talkMouseOnly |
| 1676 | SP_1676_SHOP | shop |
| 1677 | SP_1677_TRUNK | trunk |
| 1678 | SP_1678_TRUNKGET | trunkGet |
| 1679 | SP_1679_TRUNKPUT | trunkPut |
| 1680 | SP_1680_STOREBANK | storebank |
| 1681 | SP_1681_WHITE_0 | white/0 |
| 1682 | SP_1682_WHITE_1 | white/1 |
| 1683 | SP_1683_WHITE_2 | white/2 |
| 1684 | SP_1684_BLACK_0 | black/0 |
| 1685 | SP_1685_BLACK_1 | black/1 |
| 1686 | SP_1686_BLACK_2 | black/2 |
| 1687 | SP_1687_EFFECT_PETEFFIMG_BASIC_LEVELUP | Effect/PetEff.img/Basic/LevelUp |
| 1688 | SP_1688_EFFECT_PETEFFIMG_BASIC_TELEPORT | Effect/PetEff.img/Basic/Teleport |
| 1689 | SP_1689_EFFECT_PETEFFIMG_BASIC_EVOLUTION | Effect/PetEff.img/Basic/Evolution |
| 1690 | SP_1690_EFFECT_PETEFFIMG_07D_WARP | Effect/PetEff.img/%07d/warp |
| 1691 | SP_1691_STRING_PETDIALOGIMG | String/PetDialog.img |
| 1692 | SP_1692_ITEM_PET | Item/Pet |
| 1693 | SP_1693_ITEM_PET_ | Item/Pet/ |
| 1694 | SP_1694_ITEM_CASH_ | Item/Cash/ |
| 1695 | SP_1695_INTERACT | interact |
| 1696 | SP_1696_INC | inc |
| 1697 | SP_1697_PROB | prob |
| 1698 | SP_1698_COMMAND | command |
| 1699 | SP_1699_FAIL | fail |
| 1700 | SP_1700_CURSED | cursed |
| 1701 | SP_1701_RECOVER | recover |
| 1702 | SP_1702_RANDSTAT | randstat |
| 1703 | SP_1703_PREVENTSLIP | preventslip |
| 1704 | SP_1704_WARMSUPPORT | warmsupport |
| 1705 | SP_1705_FOOD | food |
| 1706 | SP_1706_MONOLOG | monolog |
| 1707 | SP_1707_PN | pn |
| 1708 | SP_1708_PT | pt |
| 1709 | SP_1709_TM | tm |
| 1710 | SP_1710_TN | tn |
| 1711 | SP_1711_PV | pv |
| 1712 | SP_1712_MAP_MAPHELPERIMG | Map/MapHelper.img |
| 1713 | SP_1713_GAME | game |
| 1714 | SP_1714_2S_2D__2D | %2s %2d : %2d |
| 1715 | SP_1715_2S_2D__00 | %2s %2d : 00 |
| 1716 | SP_1716_ITEM_SPECIAL_0900IMG | Item/Special/0900.img |
| 1717 | SP_1717_08D | %08d |
| 1718 | SP_1718_S_MAPLE04DJPG | %s\\Maple%04d.jpg |
| 1719 | SP_1719_MAPLESTORYACGUARDIAN | MapleStoryAcGuardian |
| 1720 | SP_1720_S08X | %s_%08x |
| 1721 | SP_1721_CMAPIMG_1 | cmap.img/1 |
| 1722 | SP_1722_CHARACTER_AFTERIMAGE_HITIMG_SWORD | Character/Afterimage/hit.img/sword |
| 1723 | SP_1723_CHARACTER_AFTERIMAGE_HITIMG_MACE | Character/Afterimage/hit.img/mace |
| 1724 | SP_1724_RESMAN | ResMan |
| 1725 | SP_1725_SOUNDDX8 | Sound_DX8 |
| 1726 | SP_1726_NAMESPACE | NameSpace |
| 1727 | SP_1727_NAMESPACEFILESYSTEM | NameSpace#FileSystem |
| 1728 | SP_1728_FIELDTYPE | fieldType |
| 1729 | SP_1729_STREETNAME | streetName |
| 1730 | SP_1730_MAPNAME | mapName |
| 1731 | SP_1731_MAPMARK | mapMark |
| 1732 | SP_1732_MAP_MAPHELPERIMG_MARK_ | Map/MapHelper.img/mark/ |
| 1733 | SP_1733_NONE | None |
| 1734 | SP_1734_FIELDLIMIT | fieldLimit |
| 1735 | SP_1735_AREA | area |
| 1736 | SP_1736_O | o |
| 1737 | SP_1737_HELP | help |
| 1738 | SP_1738_HELPD | help%d |
| 1739 | SP_1739_RECOVERYHP | recoveryHP |
| 1740 | SP_1740_RECOVERYMP | recoveryMP |
| 1741 | SP_1741_REDUCEHPAMOUNT | reduceHPAmount |
| 1742 | SP_1742_BUNABLETOSHOP | bUnableToShop |
| 1743 | SP_1743_BUNABLETOCHANGECHANNEL | bUnableToChangeChannel |
| 1744 | SP_1744_SEAT | seat |
| 1745 | SP_1745_TOWNPORTAL | townPortal |
| 1746 | SP_1746_TIMER | timer |
| 1747 | SP_1747_CLOCK | clock |
| 1748 | SP_1748_PAGE | page |
| 1749 | SP_1749_FC | fc |
| 1750 | SP_1750_FB | fb |
| 1751 | SP_1751_WIDTH | width |
| 1752 | SP_1752_HEIGHT | height |
| 1753 | SP_1753_CONGRATS_ON_5TH_ANNIVERSARY | Congrats on 5th Anniversary |
| 1754 | SP_1754_MAPLES_THE_BOMB | Maple's the bomb! |
| 1755 | SP_1755__M | /m |
| 1756 | SP_1756__POSITION__MAP__09D__X__D__Y__D | [POSITION] Map : %09d  X : %d  Y : %d |
| 1757 | SP_1757__POS | /pos |
| 1758 | SP_1758__CLIP | /clip |
| 1759 | SP_1759__UCLIP | /uclip |
| 1760 | SP_1760__C | /c |
| 1761 | SP_1761__BLOCK | /block |
| 1762 | SP_1762__UNBLOCK | /unblock |
| 1763 | SP_1763__NORANK | /norank |
| 1764 | SP_1764__CH | /ch |
| 1765 | SP_1765__BAN | /ban |
| 1766 | SP_1766__H | /h |
| 1767 | SP_1767__ALERTALL | /alertall |
| 1768 | SP_1768__NOTICEALL | /noticeall |
| 1769 | SP_1769__ALERTCH | /alertch |
| 1770 | SP_1770__NOTICECH | /noticech |
| 1771 | SP_1771__ALERTMAP | /alertmap |
| 1772 | SP_1772__NOTICEMAP | /noticemap |
| 1773 | SP_1773__CREATE | /create |
| 1774 | SP_1774__D | /d |
| 1775 | SP_1775__EXP | /exp |
| 1776 | SP_1776__PTON | /pton |
| 1777 | SP_1777__PTOFF | /ptoff |
| 1778 | SP_1778__PG | /pg |
| 1779 | SP_1779__VARSET | /varset |
| 1780 | SP_1780__VARGET | /varget |
| 1781 | SP_1781__TMSET | /tmset |
| 1782 | SP_1782__TMON | /tmon |
| 1783 | SP_1783__KILL | /kill |
| 1784 | SP_1784__QUESTSET | /questset |
| 1785 | SP_1785__QUESTRESET | /questreset |
| 1786 | SP_1786__SUMMON | /summon |
| 1787 | SP_1787__MOBHP | /mobhp |
| 1788 | SP_1788__LEVELSET | /levelset |
| 1789 | SP_1789__MMON | /mmon |
| 1790 | SP_1790__MMOFF | /mmoff |
| 1791 | SP_1791__SNOW | /snow |
| 1792 | SP_1792__W | /w |
| 1793 | SP_1793__MCLIP | /mclip |
| 1794 | SP_1794__DSHOP | /dshop |
| 1795 | SP_1795__FLAG | /flag |
| 1796 | SP_1796_S__S | %s : %s |
| 1797 | SP_1797_S_S | %s>> %s |
| 1798 | SP_1798_S_S__S | %s[%s]>> %s |
| 1799 | SP_1799_S_S | %s<< %s |
| 1800 | SP_1800__09D |  (%09d) |
| 1801 | SP_1801__MAP_D | /map %d |
| 1802 | SP_1802_FLOATTYPE | floatType |
| 1803 | SP_1803_PATH | path |
| 1804 | SP_1804_BGMPATH | bgmPath |
| 1805 | SP_1805_ISBGMOREFFECT | isBgmOrEffect |
| 1806 | SP_1806__ETC_OXQUIZIMG | /Etc/OXQuiz.img |
| 1807 | SP_1807_MAP_MAPHELPERIMG_PORTAL_GAME_PG | Map/MapHelper.img/portal/game/pg |
| 1808 | SP_1808_MAP_MAP | Map/Map |
| 1809 | SP_1809_ETC_TIPSIMG_INFO | Etc/Tips.img/info |
| 1810 | SP_1810_LEVELMIN | levelMin |
| 1811 | SP_1811_LEVELMAX | levelMax |
| 1812 | SP_1812_DAYBYDAY | dayByDay |
| 1813 | SP_1813_ALL | all |
| 1814 | SP_1814_TIP | tip |
| 1815 | SP_1815_ETC_TIPSIMG_ALL | Etc/Tips.img/all |
| 1816 | SP_1816_ETC_TIPSIMG_S | Etc/Tips.img/%s |
| 1817 | SP_1817_UI_UIWINDOWIMG_TRADINGROOM_BACKGRND | UI/UIWindow.img/TradingRoom/backgrnd |
| 1818 | SP_1818_UI_UIWINDOWIMG_TRADINGROOM_BTTRADE | UI/UIWindow.img/TradingRoom/BtTrade |
| 1819 | SP_1819_UI_UIWINDOWIMG_TRADINGROOM_BTCOIN | UI/UIWindow.img/TradingRoom/BtCoin |
| 1820 | SP_1820_UI_UIWINDOWIMG_TRADINGROOM_BTRESET | UI/UIWindow.img/TradingRoom/BtReset |
| 1821 | SP_1821_UI_UIWINDOWIMG_BTUICLOSE | UI/UIWindow.img/BtUIClose |
| 1822 | SP_1822_UI_UIWINDOWIMG_TRUNK_EN | UI/UIWindow.img/Trunk/en |
| 1823 | SP_1823_UI_UIWINDOWIMG_TRUNK_BTGET | UI/UIWindow.img/Trunk/BtGet |
| 1824 | SP_1824_UI_UIWINDOWIMG_TRUNK_BTPUT | UI/UIWindow.img/Trunk/BtPut |
| 1825 | SP_1825_UI_UIWINDOWIMG_TRUNK_BTSORT | UI/UIWindow.img/Trunk/BtSort |
| 1826 | SP_1826_UI_UIWINDOWIMG_TRUNK_BACKGRND | UI/UIWindow.img/Trunk/backgrnd |
| 1827 | SP_1827_UI_UIWINDOWIMG_STOREBANK_EN | UI/UIWindow.img/StoreBank/en |
| 1828 | SP_1828_UI_UIWINDOWIMG_STOREBANK_BTGETALL | UI/UIWindow.img/StoreBank/BtGetAll |
| 1829 | SP_1829_UI_UIWINDOWIMG_STOREBANK_BACKGRND | UI/UIWindow.img/StoreBank/backgrnd |
| 1830 | SP_1830_UI_UIWINDOWIMG_MULTILINE_BACKGRND2 | UI/UIWindow.img/MultiLine/backgrnd2 |
| 1831 | SP_1831_MSGIN | msgIn |
| 1832 | SP_1832_4_X_3 | 4 X 3 |
| 1833 | SP_1833_5_X_4 | 5 X 4 |
| 1834 | SP_1834_6_X_5 | 6 X 5 |
| 1835 | SP_1835_UI_UIWINDOWIMG_MINIGAMETABLE_BACKGRND | UI/UIWindow.img/MinigameTable/backgrnd |
| 1836 | SP_1836_UI_UIWINDOWIMG_MINIGAMETABLE | UI/UIWindow.img/MinigameTable |
| 1837 | SP_1837_UI_UIWINDOWIMG_MINIGAMETABLE_BTUP | UI/UIWindow.img/MinigameTable/BtUP |
| 1838 | SP_1838_UI_UIWINDOWIMG_MINIGAMETABLE_BTDOWN | UI/UIWindow.img/MinigameTable/BtDown |
| 1839 | SP_1839_BACK0 | back0 |
| 1840 | SP_1840_BACK1 | back1 |
| 1841 | SP_1841_TABLE1 | table1 |
| 1842 | SP_1842_LINE | Line |
| 1843 | SP_1843_ING0 | ing0 |
| 1844 | SP_1844_ING1 | ing1 |
| 1845 | SP_1845_UI_UIWINDOWIMG_MINIGAME_OMOK_BACKGRND2 | UI/UIWindow.img/Minigame/Omok/backgrnd2 |
| 1846 | SP_1846_UI_UIWINDOWIMG_MINIGAME_MEMORYGAME_BACKGRND2 | UI/UIWindow.img/Minigame/MemoryGame/backgrnd2 |
| 1847 | SP_1847_DRAGSTART | DragStart |
| 1848 | SP_1848_UI_UIWINDOWIMG_ITEM_BTCOIN | UI/UIWindow.img/Item/BtCoin |
| 1849 | SP_1849_UI_UIWINDOWIMG_ITEM_ACTIVEICON | UI/UIWindow.img/Item/activeIcon |
| 1850 | SP_1850_UI_UIWINDOWIMG_ITEM_BOSSPETICON | UI/UIWindow.img/Item/bossPetIcon |
| 1851 | SP_1851_UI_UIWINDOWIMG_ITEM_BTGATHER | UI/UIWindow.img/Item/BtGather |
| 1852 | SP_1852_UI_UIWINDOWIMG_ITEM_BTSORT | UI/UIWindow.img/Item/BtSort |
| 1853 | SP_1853_UI_UIWINDOWIMG_ITEM_QUALITY_0_0 | UI/UIWindow.img/Item/Quality/0/0 |
| 1854 | SP_1854_UI_UIWINDOWIMG_ITEM_QUALITY_1_0 | UI/UIWindow.img/Item/Quality/1/0 |
| 1855 | SP_1855_UI_UIWINDOWIMG_ITEM_QUALITY_2_0 | UI/UIWindow.img/Item/Quality/2/0 |
| 1856 | SP_1856_UI_UIWINDOWIMG_ITEM_QUALITY_3_0 | UI/UIWindow.img/Item/Quality/3/0 |
| 1857 | SP_1857_UI_UIWINDOWIMG_ITEM_SHADOW | UI/UIWindow.img/Item/shadow |
| 1858 | SP_1858_UI_UIWINDOWIMG_MESSENGER_BLINK | UI/UIWindow.img/Messenger/blink |
| 1859 | SP_1859__________________________ | ------------------------- |
| 1860 | SP_1860_UI_UIWINDOWIMG_MESSENGER_CHATBALLOON | UI/UIWindow.img/Messenger/chatBalloon |
| 1861 | SP_1861_UI_UIWINDOWIMG_MESSENGER_BACKGRND | UI/UIWindow.img/Messenger/backgrnd |
| 1862 | SP_1862_UI_UIWINDOWIMG_MESSENGER_BACKGRND2 | UI/UIWindow.img/Messenger/backgrnd2 |
| 1863 | SP_1863_UI_UIWINDOWIMG_MESSENGER_BACKGRND3 | UI/UIWindow.img/Messenger/backgrnd3 |
| 1864 | SP_1864_UI_UIWINDOWIMG_MESSENGER_CHARVIEW | UI/UIWindow.img/Messenger/charView |
| 1865 | SP_1865_UI_UIWINDOWIMG_MESSENGER_BTMIN | UI/UIWindow.img/Messenger/BtMin |
| 1866 | SP_1866_UI_UIWINDOWIMG_MESSENGER_BTMAX | UI/UIWindow.img/Messenger/BtMax |
| 1867 | SP_1867_UI_UIWINDOW_MESSENGER_NAMEBAR_D | UI/UIWindow/Messenger/NameBar/%d |
| 1868 | SP_1868__Q | /q |
| 1869 | SP_1869__Q | /Q |
| 1870 | SP_1870_MINIMAP | miniMap |
| 1871 | SP_1871_HIDEMINIMAP | hideMinimap |
| 1872 | SP_1872_MINIMAPONOFF | miniMapOnOff |
| 1873 | SP_1873_CANVAS | canvas |
| 1874 | SP_1874_CENTERX | centerX |
| 1875 | SP_1875_CENTERY | centerY |
| 1876 | SP_1876_MAG | mag |
| 1877 | SP_1877_LIFE | life |
| 1878 | SP_1878_PERMANENT | permanent |
| 1879 | SP_1879_PERMANENT | PERMANENT |
| 1880 | SP_1880_NOREVIVE | noRevive |
| 1881 | SP_1881_LIMITEDLIFE | limitedLife |
| 1882 | SP_1882_UI_UIWINDOWIMG_MINIMAP | UI/UIWindow.img/MiniMap |
| 1883 | SP_1883_UI_UIWINDOWIMG_ENERGYBAR | UI/UIWindow.img/EnergyBar |
| 1884 | SP_1884_UI_UIWINDOWIMG_ENERGYBAR_GAGE_D_0 | UI/UIWindow.img/EnergyBar/Gage/%d/0 |
| 1885 | SP_1885_UI_UIWINDOWIMG_ENERGYBAR_GAGE_D_1 | UI/UIWindow.img/EnergyBar/Gage/%d/1 |
| 1886 | SP_1886_DISPLAYS_THE_ENERGY_LEVEL_THATS_BEEN_CHARGED_NUSE_THE_MOUSE_TO_CHANGE_POSITION | Displays the energy level that's been charged.\nUse the mouse to change position. |
| 1887 | SP_1887_REQLEVEL | reqLevel |
| 1888 | SP_1888_MAXLEVEL | maxLevel |
| 1889 | SP_1889_REQMOBLEVEL | reqMobLevel |
| 1890 | SP_1890_REQMAP | reqMap |
| 1891 | SP_1891_UI_UIWINDOWIMG_QUEST_BACKGRND2 | UI/UIWindow.img/Quest/backgrnd2 |
| 1892 | SP_1892_UI_UIWINDOWIMG_QUEST_NOTICE_D | UI/UIWindow.img/Quest/notice/%d |
| 1893 | SP_1893_UI_UIWINDOWIMG_QUEST_NOTICED | UI/UIWindow.img/Quest/notice%d |
| 1894 | SP_1894_UI_UIWINDOWIMG_QUEST_NOTICE | UI/UIWindow.img/Quest/notice |
| 1895 | SP_1895_ETC_QUESTINFOIMG | Etc/QuestInfo.img |
| 1896 | SP_1896_UI_UIWINDOWIMG_QUEST_LINE | UI/UIWindow.img/Quest/line |
| 1897 | SP_1897_UI_UIWINDOWIMG_QUEST_ICON0 | UI/UIWindow.img/Quest/icon0 |
| 1898 | SP_1898_UI_UIWINDOWIMG_QUEST_ICON1 | UI/UIWindow.img/Quest/icon1 |
| 1899 | SP_1899_UI_UIWINDOWIMG_QUEST_ICON2 | UI/UIWindow.img/Quest/icon2 |
| 1900 | SP_1900_UI_UIWINDOWIMG_QUEST_ICON3 | UI/UIWindow.img/Quest/icon3 |
| 1901 | SP_1901_UI_UIWINDOWIMG_QUEST_ICON4 | UI/UIWindow.img/Quest/icon4 |
| 1902 | SP_1902_UI_UIWINDOWIMG_QUEST_ICON5 | UI/UIWindow.img/Quest/icon5 |
| 1903 | SP_1903_UI_UIWINDOWIMG_QUEST_ICON6 | UI/UIWindow.img/Quest/icon6 |
| 1904 | SP_1904_UI_UIWINDOWIMG_QUEST_ICON7 | UI/UIWindow.img/Quest/icon7 |
| 1905 | SP_1905_UI_UIWINDOWIMG_QUEST_ICON8 | UI/UIWindow.img/Quest/icon8 |
| 1906 | SP_1906_UI_UIWINDOWIMG_QUEST_ICON9 | UI/UIWindow.img/Quest/icon9 |
| 1907 | SP_1907_07D | %07d |
| 1908 | SP_1908_ISLAND | island |
| 1909 | SP_1909_SUBJECT | subject |
| 1910 | SP_1910_REQ | req |
| 1911 | SP_1911_PREV | prev |
| 1912 | SP_1912_NEXT | next |
| 1913 | SP_1913_END | end |
| 1914 | SP_1914_UI_BASICIMG_NOTICE_BACKGRND | UI/Basic.img/Notice/backgrnd |
| 1915 | SP_1915_UI_BASICIMG_YESNO_BACKGRND | UI/Basic.img/YesNo/backgrnd |
| 1916 | SP_1916_UI_BASICIMG_BTYES | UI/Basic.img/BtYes |
| 1917 | SP_1917_UI_BASICIMG_BTNO | UI/Basic.img/BtNo |
| 1918 | SP_1918__I |  I |
| 1919 | SP_1919__II |  II |
| 1920 | SP_1920__III |  III |
| 1921 | SP_1921__IV |  IV |
| 1922 | SP_1922__V |  V |
| 1923 | SP_1923_UI_UIWINDOWIMG_SKILL_BTSPUP | UI/UIWindow.img/Skill/BtSpUp |
| 1924 | SP_1924_UI_UIWINDOWIMG_SKILL_SKILL0 | UI/UIWindow.img/Skill/skill0 |
| 1925 | SP_1925_UI_UIWINDOWIMG_SKILL_SKILL1 | UI/UIWindow.img/Skill/skill1 |
| 1926 | SP_1926_UI_UIWINDOWIMG_SKILL_RECOMMEND_0 | UI/UIWindow.img/Skill/recommend/0 |
| 1927 | SP_1927_UI_UIWINDOWIMG_SKILLEX_BACKGRND | UI/UIWindow.img/SkillEx/backgrnd |
| 1928 | SP_1928_UI_UIWINDOWIMG_SKILLEX_BTSPUP | UI/UIWindow.img/SkillEx/BtSpUp |
| 1929 | SP_1929_UI_UIWINDOWIMG_SKILLEX_BTDLG | UI/UIWindow.img/SkillEx/BtDlg |
| 1930 | SP_1930_UI_UIWINDOWIMG_SKILLEX_SKILL0 | UI/UIWindow.img/SkillEx/skill0 |
| 1931 | SP_1931_UI_UIWINDOWIMG_SKILLEX_SKILL1 | UI/UIWindow.img/SkillEx/skill1 |
| 1932 | SP_1932_UI_UIWINDOWIMG_SKILLEX_RECOMMEND_0 | UI/UIWindow.img/SkillEx/recommend/0 |
| 1933 | SP_1933_UI_UIWINDOWIMG_SKILLEX_SPNUM | UI/UIWindow.img/SkillEx/SpNum |
| 1934 | SP_1934_UI_UIWINDOWIMG_SKILLEX_GLOW | UI/UIWindow.img/SkillEx/Glow |
| 1935 | SP_1935_UI_UIWINDOWIMG_SKILLEX_DRAGON_D | UI/UIWindow.img/SkillEx/Dragon/%d |
| 1936 | SP_1936_UI_UIWINDOWIMG_SKILLEX_EGG_D | UI/UIWindow.img/SkillEx/egg/%d |
| 1937 | SP_1937_UI_UIWINDOWIMG_STAT_BACKGRND2 | UI/UIWindow.img/Stat/backgrnd2 |
| 1938 | SP_1938_STRING_TOOLTIPHELPIMG_GAME_UIWND_STATDETAIL | String/ToolTipHelp.img/Game/UIWnd/StatDetail |
| 1939 | SP_1939_D_DD | %d (%d%+d) |
| 1940 | SP_1940_D__D | %d ~ %d |
| 1941 | SP_1941_D | %d%% |
| 1942 | SP_1942_D | +%d |
| 1943 | SP_1943_UI_UIWINDOWIMG_STAT_BTAPUP | UI/UIWindow.img/Stat/BtApUp |
| 1944 | SP_1944_UI_UIWINDOWIMG_STAT_BTAUTO | UI/UIWindow.img/Stat/BtAuto |
| 1945 | SP_1945_UI_UIWINDOWIMG_STAT_BTAUTO1 | UI/UIWindow.img/Stat/BtAuto1 |
| 1946 | SP_1946_UI_UIWINDOWIMG_STAT_BTAUTO2 | UI/UIWindow.img/Stat/BtAuto2 |
| 1947 | SP_1947_UI_UIWINDOWIMG_STAT_DISABLED_ | UI/UIWindow.img/Stat/Disabled/ |
| 1948 | SP_1948_STRING_TOOLTIPHELPIMG_GAME_BUTTON_STATAUTO | String/ToolTipHelp.img/Game/Button/StatAuto |
| 1949 | SP_1949_STRING_TOOLTIPHELPIMG_GAME_BUTTON_STATAUTO1 | String/ToolTipHelp.img/Game/Button/StatAuto1 |
| 1950 | SP_1950_STRING_TOOLTIPHELPIMG_GAME_BUTTON_STATAUTO2 | String/ToolTipHelp.img/Game/Button/StatAuto2 |
| 1951 | SP_1951_____YOUR_AP_WILL_BE_DISTRIBUTED_AS_FOLLOWS_R_N_R_N |     Your AP will be distributed as follows:\r\n\r\n |
| 1952 | SP_1952______S__D_R_N |      %s : +%d\r\n |
| 1953 | SP_1953_STRING_TOOLTIPHELPIMG_GAME_UIWND_STAT | String/ToolTipHelp.img/Game/UIWnd/Stat |
| 1954 | SP_1954_D_D | %d (%d%%) |
| 1955 | SP_1955_UI_UIWINDOWIMG_STAT_JOB | UI/UIWindow.img/Stat/Job |
| 1956 | SP_1956_UI_STATUSBARIMG | UI/StatusBar.img |
| 1957 | SP_1957_UI_STATUSBARIMG_GAUGE_BAR | UI/StatusBar.img/gauge/bar |
| 1958 | SP_1958_UI_STATUSBARIMG_GAUGE_GRAY | UI/StatusBar.img/gauge/gray |
| 1959 | SP_1959_UI_STATUSBARIMG_GAUGE_TEMPEXP | UI/StatusBar.img/gauge/tempExp |
| 1960 | SP_1960_GAUGE | gauge |
| 1961 | SP_1961_HP | hp |
| 1962 | SP_1962_MP | mp |
| 1963 | SP_1963_PAD | pad |
| 1964 | SP_1964_PDD | pdd |
| 1965 | SP_1965_MAD | mad |
| 1966 | SP_1966_MDD | mdd |
| 1967 | SP_1967_CRAFT | craft |
| 1968 | SP_1968_HPCON | hpCon |
| 1969 | SP_1969_MPCON | mpCon |
| 1970 | SP_1970_MONEYCON | moneyCon |
| 1971 | SP_1971_ITEMCON | itemCon |
| 1972 | SP_1972_ITEMCONNO | itemConNo |
| 1973 | SP_1973_UI_STATUSBARIMG_BTSHOP | UI/StatusBar.img/BtShop |
| 1974 | SP_1974_UI_STATUSBARIMG_BTSHORT | UI/StatusBar.img/BtShort |
| 1975 | SP_1975_UI_STATUSBARIMG_BTMENU | UI/StatusBar.img/BtMenu |
| 1976 | SP_1976_UI_STATUSBARIMG_BTCLAIM | UI/StatusBar.img/BtClaim |
| 1977 | SP_1977_UI_STATUSBARIMG_BTKEY | UI/StatusBar.img/BtKey |
| 1978 | SP_1978_UI_STATUSBARIMG_BTQUICK | UI/StatusBar.img/BtQuick |
| 1979 | SP_1979_UI_STATUSBARIMG_EQUIPKEY | UI/StatusBar.img/EquipKey |
| 1980 | SP_1980_UI_STATUSBARIMG_INVENKEY | UI/StatusBar.img/InvenKey |
| 1981 | SP_1981_UI_STATUSBARIMG_STATKEY | UI/StatusBar.img/StatKey |
| 1982 | SP_1982_UI_STATUSBARIMG_SKILLKEY | UI/StatusBar.img/SkillKey |
| 1983 | SP_1983_UI_STATUSBARIMG_KEYSET | UI/StatusBar.img/KeySet |
| 1984 | SP_1984_UI_STATUSBARIMG_QUICKSLOT | UI/StatusBar.img/QuickSlot |
| 1985 | SP_1985_UI_STATUSBARIMG_QUICKSLOTD | UI/StatusBar.img/QuickSlotD |
| 1986 | SP_1986_UI_STATUSBARIMG_EQUIPKEY_ANI | UI/StatusBar.img/EquipKey/ani |
| 1987 | SP_1987_UI_STATUSBARIMG_INVENKEY_ANI | UI/StatusBar.img/InvenKey/ani |
| 1988 | SP_1988_UI_STATUSBARIMG_STATKEY_ANI | UI/StatusBar.img/StatKey/ani |
| 1989 | SP_1989_UI_STATUSBARIMG_SKILLKEY_ANI | UI/StatusBar.img/SkillKey/ani |
| 1990 | SP_1990_STRING_TOOLTIPHELPIMG_GAME_BUTTON_SHOP | String/ToolTipHelp.img/Game/Button/Shop |
| 1991 | SP_1991_STRING_TOOLTIPHELPIMG_GAME_BUTTON_ITEM | String/ToolTipHelp.img/Game/Button/Item |
| 1992 | SP_1992_STRING_TOOLTIPHELPIMG_GAME_BUTTON_EQUIP | String/ToolTipHelp.img/Game/Button/Equip |
| 1993 | SP_1993_STRING_TOOLTIPHELPIMG_GAME_BUTTON_CLAIM | String/ToolTipHelp.img/Game/Button/Claim |
| 1994 | SP_1994_STRING_TOOLTIPHELPIMG_GAME_BUTTON_STAT | String/ToolTipHelp.img/Game/Button/Stat |
| 1995 | SP_1995_STRING_TOOLTIPHELPIMG_GAME_BUTTON_SKILL | String/ToolTipHelp.img/Game/Button/Skill |
| 1996 | SP_1996_STRING_TOOLTIPHELPIMG_GAME_BUTTON_CHAT | String/ToolTipHelp.img/Game/Button/Chat |
| 1997 | SP_1997_STRING_TOOLTIPHELPIMG_GAME_BUTTON_MENU | String/ToolTipHelp.img/Game/Button/Menu |
| 1998 | SP_1998_STRING_TOOLTIPHELPIMG_GAME_BUTTON_KEYSET | String/ToolTipHelp.img/Game/Button/KeySet |
| 1999 | SP_1999_STRING_TOOLTIPHELPIMG_GAME_BUTTON_QUICKSLOT | String/ToolTipHelp.img/Game/Button/QuickSlot |
| 2000 | SP_2000_STRING_TOOLTIPHELPIMG_GAME_BUTTON_QUICKSLOTD | String/ToolTipHelp.img/Game/Button/QuickSlotD |
| 2001 | SP_2001_STRING_TOOLTIPHELPIMG_GAME_BUTTON_QUESTALARM | String/ToolTipHelp.img/Game/Button/QuestAlarm |
| 2002 | SP_2002_BASE | base |
| 2003 | SP_2003_S___S___S | %s / %s / %s |
| 2004 | SP_2004_HP__D___D | HP : %d / %d |
| 2005 | SP_2005_MP__D___D | MP : %d / %d |
| 2006 | SP_2006_LEVEL__D | Level : %d |
| 2007 | SP_2007__REQUIRED_ | [Required] |
| 2008 | SP_2008_LVD | Lv.%d |
| 2009 | SP_2009_2F | %.2f%% |
| 2010 | SP_2010_UI_UIWINDOWIMG_TOOLTIP_EQUIP_CAN | UI/UIWindow.img/ToolTip/Equip/Can |
| 2011 | SP_2011_UI_UIWINDOWIMG_TOOLTIP_EQUIP_CANNOT | UI/UIWindow.img/ToolTip/Equip/Cannot |
| 2012 | SP_2012_REQLEV | reqLEV |
| 2013 | SP_2013_REQSTR | reqSTR |
| 2014 | SP_2014_REQDEX | reqDEX |
| 2015 | SP_2015_REQINT | reqINT |
| 2016 | SP_2016_REQLUK | reqLUK |
| 2017 | SP_2017_REQPOP | reqPOP |
| 2018 | SP_2018_REQJOB | reqJob |
| 2019 | SP_2019_REQRUC | reqRUC |
| 2020 | SP_2020_TUC | tuc |
| 2021 | SP_2021_INCSTR | incSTR |
| 2022 | SP_2022_INCDEX | incDEX |
| 2023 | SP_2023_INCINT | incINT |
| 2024 | SP_2024_INCLUK | incLUK |
| 2025 | SP_2025_INCMHP | incMHP |
| 2026 | SP_2026_INCMMP | incMMP |
| 2027 | SP_2027_INCPAD | incPAD |
| 2028 | SP_2028_INCPDD | incPDD |
| 2029 | SP_2029_INCMAD | incMAD |
| 2030 | SP_2030_INCMDD | incMDD |
| 2031 | SP_2031_INCACC | incACC |
| 2032 | SP_2032_INCEVA | incEVA |
| 2033 | SP_2033_INCCRAFT | incCraft |
| 2034 | SP_2034_INCSPEED | incSpeed |
| 2035 | SP_2035_INCJUMP | incJump |
| 2036 | SP_2036_INCSWIM | incSwim |
| 2037 | SP_2037_CASH | cash |
| 2038 | SP_2038_NOMOVETOLOCKER | noMoveToLocker |
| 2039 | SP_2039_KNOCKBACK | knockback |
| 2040 | SP_2040_S_D | %s (+%d) |
| 2041 | SP_2041_S_DS | %s (+%d%s) |
| 2042 | SP_2042_STR_ | STR : |
| 2043 | SP_2043_DEX_ | DEX : |
| 2044 | SP_2044_INT_ | INT : |
| 2045 | SP_2045_LUK_ | LUK : |
| 2046 | SP_2046_HP_ | HP : |
| 2047 | SP_2047_MP_ | MP : |
| 2048 | SP_2048__D |  +%d |
| 2049 | SP_2049___D |  -%d |
| 2050 | SP_2050_DESCD | descD |
| 2051 | SP_2051_UI_UIWINDOWIMG_USERINFO_BACKGRND | UI/UIWindow.img/UserInfo/backgrnd |
| 2052 | SP_2052_UI_UIWINDOWIMG_USERINFO_BACKGRND2 | UI/UIWindow.img/UserInfo/backgrnd2 |
| 2053 | SP_2053_UI_UIWINDOWIMG_USERINFO_BACKGRND3 | UI/UIWindow.img/UserInfo/backgrnd3 |
| 2054 | SP_2054_UI_UIWINDOWIMG_USERINFO_BACKGRND4 | UI/UIWindow.img/UserInfo/backgrnd4 |
| 2055 | SP_2055_UI_UIWINDOWIMG_USERINFO_BACKGRND5 | UI/UIWindow.img/UserInfo/backgrnd5 |
| 2056 | SP_2056_UI_UIWINDOWIMG_USERINFO_BACKGRND6 | UI/UIWindow.img/UserInfo/backgrnd6 |
| 2057 | SP_2057_UI_UIWINDOWIMG_USERINFO_BACKGRND7 | UI/UIWindow.img/UserInfo/backgrnd7 |
| 2058 | SP_2058_UI_UIWINDOWIMG_EXCEPTIONITEMSEARCH_BACKGRND | UI/UIWindow.img/ExceptionItemSearch/backgrnd |
| 2059 | SP_2059_UI_UIWINDOWIMG_EXCEPTIONITEMSEARCH_BTSEARCH | UI/UIWindow.img/ExceptionItemSearch/BtSearch |
| 2060 | SP_2060_UI_UIWINDOWIMG_EXCEPTIONITEMSEARCH_RESULTBACK | UI/UIWindow.img/ExceptionItemSearch/resultback |
| 2061 | SP_2061_UI_UIWINDOWIMG_EXCEPTIONITEMSEARCH_BTSAVE | UI/UIWindow.img/ExceptionItemSearch/BtSave |
| 2062 | SP_2062_UI_UIWINDOWIMG_EXCEPTIONITEMSEARCH_BTDELETE | UI/UIWindow.img/ExceptionItemSearch/BtDelete |
| 2063 | SP_2063_REQLV__D | ReqLv : %d |
| 2064 | SP_2064_UI_UIWINDOWIMG_STAT_BTDETAIL | UI/UIWindow.img/Stat/BtDetail |
| 2065 | SP_2065_UI_UIWINDOWIMG_EQUIP_BTDETAIL | UI/UIWindow.img/Equip/BtDetail |
| 2066 | SP_2066_UI_UIWINDOWIMG_USERLIST_FRIEND | UI/UIWindow.img/UserList/Friend |
| 2067 | SP_2067_FRIEND0 | friend0 |
| 2068 | SP_2068_FRIEND1 | friend1 |
| 2069 | SP_2069_FRIEND2 | friend2 |
| 2070 | SP_2070_FRIEND3 | friend3 |
| 2071 | SP_2071_FRIEND4 | friend4 |
| 2072 | SP_2072_FRIEND5 | friend5 |
| 2073 | SP_2073_FRIEND6 | friend6 |
| 2074 | SP_2074_BUTTONBG | buttonbg |
| 2075 | SP_2075_ICON0 | icon0 |
| 2076 | SP_2076_ICON1 | icon1 |
| 2077 | SP_2077_ICON2 | icon2 |
| 2078 | SP_2078_ICON3 | icon3 |
| 2079 | SP_2079_ICON4 | icon4 |
| 2080 | SP_2080_ICON5 | icon5 |
| 2081 | SP_2081_D_D | %d/%d |
| 2082 | SP_2082__D_D_ | [%d/%d] |
| 2083 | SP_2083_UI_UIWINDOWIMG_USERLIST_PARTY | UI/UIWindow.img/UserList/Party |
| 2084 | SP_2084_PARTY0 | party0 |
| 2085 | SP_2085_PARTY1 | party1 |
| 2086 | SP_2086_PARTY2 | party2 |
| 2087 | SP_2087_PARTY3 | party3 |
| 2088 | SP_2088_PARTY4 | party4 |
| 2089 | SP_2089_PARTY5 | party5 |
| 2090 | SP_2090_UI_UIWINDOWIMG_USERLIST_BACKGRND | UI/UIWindow.img/UserList/backgrnd |
| 2091 | SP_2091_UI_UIWINDOWIMG_USERLIST_BACKGRND2 | UI/UIWindow.img/UserList/backgrnd2 |
| 2092 | SP_2092_UI_UIWINDOWIMG_USERLIST_BACKGRND3 | UI/UIWindow.img/UserList/backgrnd3 |
| 2093 | SP_2093_UI_UIWINDOWIMG_USERLIST_FRIEND_GROUPMOD_BACKGRND | UI/UIWindow.img/UserList/Friend/GroupMod/backgrnd |
| 2094 | SP_2094_UI_UIWINDOWIMG_USERLIST_FRIEND_GROUPMOD_BTOK | UI/UIWindow.img/UserList/Friend/GroupMod/BtOk |
| 2095 | SP_2095_UI_UIWINDOWIMG_USERLIST_FRIEND_GROUPMOD_BTCANCEL | UI/UIWindow.img/UserList/Friend/GroupMod/BtCancel |
| 2096 | SP_2096_UI_UIWINDOWIMG_USERLIST_FRIEND_BTADD | UI/UIWindow.img/UserList/Friend/BtAdd |
| 2097 | SP_2097_UI_UIWINDOWIMG_USERLIST_FRIEND_BTMOD | UI/UIWindow.img/UserList/Friend/BtMod |
| 2098 | SP_2098_UI_UIWINDOWIMG_USERLIST_FRIEND_BTDELETE | UI/UIWindow.img/UserList/Friend/BtDelete |
| 2099 | SP_2099_UI_UIWINDOWIMG_USERLIST_FRIEND_BTADDFRIEND | UI/UIWindow.img/UserList/Friend/BtAddFriend |
| 2100 | SP_2100_UI_UIWINDOWIMG_USERLIST_FRIEND_BTADDGROUP | UI/UIWindow.img/UserList/Friend/BtAddGroup |
| 2101 | SP_2101_UI_UIWINDOWIMG_USERLIST_FRIEND_BTWHISPER | UI/UIWindow.img/UserList/Friend/BtWhisper |
| 2102 | SP_2102_UI_UIWINDOWIMG_USERLIST_FRIEND_BTGROUPWHISPER | UI/UIWindow.img/UserList/Friend/BtGroupWhisper |
| 2103 | SP_2103_UI_UIWINDOWIMG_USERLIST_FRIEND_BTBLOCK | UI/UIWindow.img/UserList/Friend/BtBlock |
| 2104 | SP_2104_UI_UIWINDOWIMG_USERLIST_FRIEND_BTUNBLOCK | UI/UIWindow.img/UserList/Friend/BtUnBlock |
| 2105 | SP_2105_UI_UIWINDOWIMG_USERLIST_FRIEND_BTCHAT | UI/UIWindow.img/UserList/Friend/BtChat |
| 2106 | SP_2106_UI_UIWINDOWIMG_USERLIST_FRIEND_BTPARTY | UI/UIWindow.img/UserList/Friend/BtParty |
| 2107 | SP_2107_UI_UIWINDOWIMG_USERLIST_FRIEND_BTMESSAGE | UI/UIWindow.img/UserList/Friend/BtMessage |
| 2108 | SP_2108_UI_UIWINDOWIMG_USERLIST_FRIEND_BTSHOWALL | UI/UIWindow.img/UserList/Friend/BtShowAll |
| 2109 | SP_2109_UI_UIWINDOWIMG_USERLIST_FRIEND_BTSHOWONLINE | UI/UIWindow.img/UserList/Friend/BtShowOnline |
| 2110 | SP_2110_UI_UIWINDOWIMG_USERLIST_PARTY_BTCREATE | UI/UIWindow.img/UserList/Party/BtCreate |
| 2111 | SP_2111_UI_UIWINDOWIMG_USERLIST_PARTY_BTINVITE | UI/UIWindow.img/UserList/Party/BtInvite |
| 2112 | SP_2112_UI_UIWINDOWIMG_USERLIST_PARTY_BTKICK | UI/UIWindow.img/UserList/Party/BtKick |
| 2113 | SP_2113_UI_UIWINDOWIMG_USERLIST_PARTY_BTWITHDRAW | UI/UIWindow.img/UserList/Party/BtWithdraw |
| 2114 | SP_2114_UI_UIWINDOWIMG_USERLIST_PARTY_BTWHISPER | UI/UIWindow.img/UserList/Party/BtWhisper |
| 2115 | SP_2115_UI_UIWINDOWIMG_USERLIST_PARTY_BTCHAT | UI/UIWindow.img/UserList/Party/BtChat |
| 2116 | SP_2116_UI_UIWINDOWIMG_USERLIST_PARTY_BTHP | UI/UIWindow.img/UserList/Party/BtHP |
| 2117 | SP_2117_UI_UIWINDOWIMG_USERLIST_PARTY_BTSEARCH | UI/UIWindow.img/UserList/Party/BtSearch |
| 2118 | SP_2118_UI_UIWINDOWIMG_USERLIST_PARTY_PARTYHP | UI/UIWindow.img/UserList/Party/PartyHP |
| 2119 | SP_2119_UI_UIWINDOWIMG_GUILD_GUILDINFO | UI/UIWindow.img/Guild/GuildInfo |
| 2120 | SP_2120_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO | UI/UIWindow.img/UserList/Guild/GuildInfo |
| 2121 | SP_2121_UI_UIWINDOWIMG_USERLIST_GUILD_MAKE_BACKGRND | UI/UIWindow.img/UserList/Guild/Make/backgrnd |
| 2122 | SP_2122_UI_UIWINDOWIMG_USERLIST_GUILD_MAKE_MESSAGE | UI/UIWindow.img/UserList/Guild/Make/message |
| 2123 | SP_2123_UI_UIWINDOWIMG_USERLIST_GUILD_MAKE_BTAGREE | UI/UIWindow.img/UserList/Guild/Make/BtAgree |
| 2124 | SP_2124_UI_UIWINDOWIMG_USERLIST_GUILD_MAKE_BTDISAGREE | UI/UIWindow.img/UserList/Guild/Make/BtDisagree |
| 2125 | SP_2125_UI_UIWINDOWIMG_PARTYSEARCH_BACKGRND | UI/UIWindow.img/PartySearch/backgrnd |
| 2126 | SP_2126_GUILDMARK | guildmark |
| 2127 | SP_2127_GUILDINFO0 | guildinfo0 |
| 2128 | SP_2128_GUILDINFO1 | guildinfo1 |
| 2129 | SP_2129_GUILDINFO2 | guildinfo2 |
| 2130 | SP_2130_GUILDINFO3 | guildinfo3 |
| 2131 | SP_2131_GUILDINFO4 | guildinfo4 |
| 2132 | SP_2132_GUILDINFO5 | guildinfo5 |
| 2133 | SP_2133_GUILDINFO6 | guildinfo6 |
| 2134 | SP_2134_GUILDINFO7 | guildinfo7 |
| 2135 | SP_2135_UI_UIWINDOWIMG_GUILD_BACKGRND | UI/UIWindow.img/Guild/backgrnd |
| 2136 | SP_2136_UI_UIWINDOWIMG_GUILD_BACKGRND2 | UI/UIWindow.img/Guild/backgrnd2 |
| 2137 | SP_2137_UI_UIWINDOWIMG_GUILD_GUILDINFO_BTCREATE | UI/UIWindow.img/Guild/GuildInfo/BtCreate |
| 2138 | SP_2138_UI_UIWINDOWIMG_GUILD_GUILDINFO_BTINVITE | UI/UIWindow.img/Guild/GuildInfo/BtInvite |
| 2139 | SP_2139_UI_UIWINDOWIMG_GUILD_GUILDINFO_BTKICK | UI/UIWindow.img/Guild/GuildInfo/BtKick |
| 2140 | SP_2140_UI_UIWINDOWIMG_GUILD_GUILDINFO_BTWITHDRAW | UI/UIWindow.img/Guild/GuildInfo/BtWithdraw |
| 2141 | SP_2141_UI_UIWINDOWIMG_GUILD_GUILDINFO_BTWHISPER | UI/UIWindow.img/Guild/GuildInfo/BtWhisper |
| 2142 | SP_2142_UI_UIWINDOWIMG_GUILD_GUILDINFO_BTCHAT | UI/UIWindow.img/Guild/GuildInfo/BtChat |
| 2143 | SP_2143_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTCREATE | UI/UIWindow.img/UserList/Guild/GuildInfo/BtCreate |
| 2144 | SP_2144_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTINVITE | UI/UIWindow.img/UserList/Guild/GuildInfo/BtInvite |
| 2145 | SP_2145_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTKICK | UI/UIWindow.img/UserList/Guild/GuildInfo/BtKick |
| 2146 | SP_2146_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTWITHDRAW | UI/UIWindow.img/UserList/Guild/GuildInfo/BtWithdraw |
| 2147 | SP_2147_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTWHISPER | UI/UIWindow.img/UserList/Guild/GuildInfo/BtWhisper |
| 2148 | SP_2148_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTCHAT | UI/UIWindow.img/UserList/Guild/GuildInfo/BtChat |
| 2149 | SP_2149_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTINFO | UI/UIWindow.img/UserList/Guild/GuildInfo/BtInfo |
| 2150 | SP_2150_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTUP | UI/UIWindow.img/UserList/Guild/GuildInfo/BtUp |
| 2151 | SP_2151_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTDOWN | UI/UIWindow.img/UserList/Guild/GuildInfo/BtDown |
| 2152 | SP_2152_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTNOTICE | UI/UIWindow.img/UserList/Guild/GuildInfo/Btnotice |
| 2153 | SP_2153_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTPARTYINVITE | UI/UIWindow.img/UserList/Guild/GuildInfo/BtPartyInvite |
| 2154 | SP_2154_GUILDGRADE0 | guildgrade0 |
| 2155 | SP_2155_GUILDGRADE1 | guildgrade1 |
| 2156 | SP_2156_GUILDGRADE2 | guildgrade2 |
| 2157 | SP_2157_GUILDGRADE3 | guildgrade3 |
| 2158 | SP_2158_GUILDGRADE4 | guildgrade4 |
| 2159 | SP_2159_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDGRADE_BTMODF | UI/UIWindow.img/UserList/Guild/GuildGrade/BtModf |
| 2160 | SP_2160_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDGRADE_BTSAVE | UI/UIWindow.img/UserList/Guild/GuildGrade/BtSave |
| 2161 | SP_2161_BLACKLIST0 | blacklist0 |
| 2162 | SP_2162_BLACKLIST1 | blacklist1 |
| 2163 | SP_2163_BLACKLIST2 | blacklist2 |
| 2164 | SP_2164_UI_UIWINDOWIMG_USERLIST_BLACKLIST | UI/UIWindow.img/UserList/BlackList |
| 2165 | SP_2165_UI_UIWINDOWIMG_USERLIST_BLACKLIST_BTADD | UI/UIWindow.img/UserList/BlackList/BtAdd |
| 2166 | SP_2166_UI_UIWINDOWIMG_USERLIST_BLACKLIST_BTDELETE | UI/UIWindow.img/UserList/BlackList/BtDelete |
| 2167 | SP_2167_UI_UIWINDOWIMG_S_BACKGRND | UI/UIWindow.img/%s/backgrnd |
| 2168 | SP_2168_UI_UIWINDOWIMG_S_BTCLOSE | UI/UIWindow.img/%s/BtClose |
| 2169 | SP_2169_EQUIP | Equip |
| 2170 | SP_2170_STAT | Stat |
| 2171 | SP_2171_SKILL | Skill |
| 2172 | SP_2172_SKILLEX | SkillEx |
| 2173 | SP_2173_SKILL_ | Skill/ |
| 2174 | SP_2174_BOOKNAME | bookName |
| 2175 | SP_2175_MINIMAP | MiniMap |
| 2176 | SP_2176_USERINFO | UserInfo |
| 2177 | SP_2177_FAMILYTREE | FamilyTree |
| 2178 | SP_2178_PETINFO | PetInfo |
| 2179 | SP_2179_QUEST | Quest |
| 2180 | SP_2180_EFFECT_BASICEFFIMG_TELEPORT | Effect/BasicEff.img/Teleport |
| 2181 | SP_2181_EFFECT_TOMBIMG_FALL | Effect/Tomb.img/fall |
| 2182 | SP_2182_ETC_FORBIDDENNAMEIMG | Etc/ForbiddenName.img |
| 2183 | SP_2183_ERROR_CODE__D_S | error code : %d (%s) |
| 2184 | SP_2184_ERROR_CODE__D_S_R_NS | error code : %d (%s)\r\n%s |
| 2185 | SP_2185_S_SHELL_OPEN_COMMAND | %s\\shell\\open\\command |
| 2186 | SP_2186_1 | %1 |
| 2187 | SP_2187__1 |  %1 |
| 2188 | SP_2188_HTM | .htm |
| 2189 | SP_2189_S_MAPLEEXPLOREREXE_S | %s\\MapleExplorer.exe %s |
| 2190 | SP_2190_CHS | CH.%s |
| 2191 | SP_2191_I64D | %I64d |
| 2192 | SP_2192_ | .. |
| 2193 | SP_2193_SOUND_UIIMG_ | Sound/UI.img/ |
| 2194 | SP_2194_USE2 | Use2 |
| 2195 | SP_2195_SOUND_ITEMIMG_08D_S | Sound/Item.img/%08d/%s |
| 2196 | SP_2196_SOUND_SKILLIMG_07D_S | Sound/Skill.img/%07d/%s |
| 2197 | SP_2197_SOUND_MOBIMG_07D_S | Sound/Mob.img/%07d/%s |
| 2198 | SP_2198_SOUND_SUMMONIMG_D | Sound/Summon.img/%d |
| 2199 | SP_2199_SOUND_WEAPONIMG_S_S | Sound/Weapon.img/%s/%s |
| 2200 | SP_2200_SOUND_QUESTIMG_D | Sound/Quest.img/%d |
| 2201 | SP_2201_SOUND_PETIMG_BASIC_S | Sound/Pet.img/Basic/%s |
| 2202 | SP_2202_SOUND_PETIMG_07D_S | Sound/Pet.img/%07d/%s |
| 2203 | SP_2203_SOUND_GAMEIMG_ | Sound/Game.img/ |
| 2204 | SP_2204_SOUND_MINIGAMEIMG_ | Sound/MiniGame.img/ |
| 2205 | SP_2205_SOUND_OBJECTIMG_ | Sound/Object.img/ |
| 2206 | SP_2206_IMG | .img |
| 2207 | SP_2207_UI_UIWINDOWIMG_GAMEMENU_BTSKIN | UI/UIWindow.img/GameMenu/BtSkin |
| 2208 | SP_2208_UI_UIWINDOWIMG_GAMEMENU_BTCHANNEL | UI/UIWindow.img/GameMenu/BtChannel |
| 2209 | SP_2209_UI_UIWINDOWIMG_GAMEMENU_BTGAMEOPT | UI/UIWindow.img/GameMenu/BtGameOpt |
| 2210 | SP_2210_UI_UIWINDOWIMG_GAMEMENU_BTSYSOPT | UI/UIWindow.img/GameMenu/BtSysOpt |
| 2211 | SP_2211_UI_UIWINDOWIMG_GAMEMENU_BTQUIT | UI/UIWindow.img/GameMenu/BtQuit |
| 2212 | SP_2212_UI_UIWINDOWIMG_CHANNEL_BTCHANGE | UI/UIWindow.img/Channel/BtChange |
| 2213 | SP_2213_UI_UIWINDOWIMG_CHANNEL_BTCANCEL | UI/UIWindow.img/Channel/BtCancel |
| 2214 | SP_2214_UI_UIWINDOWIMG_CHANNEL | UI/UIWindow.img/Channel |
| 2215 | SP_2215_T | t |
| 2216 | SP_2216_P | p |
| 2217 | SP_2217_M | m |
| 2218 | SP_2218_Q | q |
| 2219 | SP_2219_D | d |
| 2220 | SP_2220_O | O |
| 2221 | SP_2221_X | X |
| 2222 | SP_2222_CHANNEL | channel |
| 2223 | SP_2223_UI_UIWINDOWIMG_CONTEXTMENU | UI/UIWindow.img/ContextMenu |
| 2224 | SP_2224_UI_UIWINDOWIMG_GAMEMENU_BACKGRND | UI/UIWindow.img/GameMenu/backgrnd |
| 2225 | SP_2225_UI_UIWINDOWIMG_GAMEOPT_BACKGRND | UI/UIWindow.img/GameOpt/backgrnd |
| 2226 | SP_2226_UI_UIWINDOWIMG_SYSOPT_BACKGRND | UI/UIWindow.img/SysOpt/backgrnd |
| 2227 | SP_2227_UI_UIWINDOWIMG_SHORTCUT_BACKGRND | UI/UIWindow.img/ShortCut/backgrnd |
| 2228 | SP_2228_UI_UIWINDOWIMG_SHORTCUT_BTITEM | UI/UIWindow.img/ShortCut/BtItem |
| 2229 | SP_2229_UI_UIWINDOWIMG_SHORTCUT_BTEQUIP | UI/UIWindow.img/ShortCut/BtEquip |
| 2230 | SP_2230_UI_UIWINDOWIMG_SHORTCUT_BTSTAT | UI/UIWindow.img/ShortCut/BtStat |
| 2231 | SP_2231_UI_UIWINDOWIMG_SHORTCUT_BTSKILL | UI/UIWindow.img/ShortCut/BtSkill |
| 2232 | SP_2232_UI_UIWINDOWIMG_SHORTCUT_BTPARTY | UI/UIWindow.img/ShortCut/BtParty |
| 2233 | SP_2233_UI_UIWINDOWIMG_SHORTCUT_BTGUILD | UI/UIWindow.img/ShortCut/BtGuild |
| 2234 | SP_2234_UI_UIWINDOWIMG_SHORTCUT_BTQUEST | UI/UIWindow.img/ShortCut/BtQuest |
| 2235 | SP_2235_UI_UIWINDOWIMG_SHORTCUT_BTCOMM | UI/UIWindow.img/ShortCut/BtComm |
| 2236 | SP_2236_UI_UIWINDOWIMG_SHORTCUT_BTMOBBOOK | UI/UIWindow.img/ShortCut/BtMobbook |
| 2237 | SP_2237_UI_UIWINDOWIMG_SHORTCUT_BTMESSENGER | UI/UIWindow.img/ShortCut/BtMessenger |
| 2238 | SP_2238_UI_UIWINDOWIMG_SHORTCUT_BTRANKING | UI/UIWindow.img/ShortCut/BtRanking |
| 2239 | SP_2239_STRING_MONSTERBOOKIMG | String/MonsterBook.img |
| 2240 | SP_2240_STRING_MOBIMG | String/Mob.img |
| 2241 | SP_2241_STRING_SKILLIMG | String/Skill.img |
| 2242 | SP_2242_EPISODE | episode |
| 2243 | SP_2243_REWARD | reward |
| 2244 | SP_2244_K | #k |
| 2245 | SP_2245_R | #r |
| 2246 | SP_2246_G | #g |
| 2247 | SP_2247_B | #b |
| 2248 | SP_2248_E | #e |
| 2249 | SP_2249_N | #n |
| 2250 | SP_2250_L | #l |
| 2251 | SP_2251_D | #d |
| 2252 | SP_2252_UI_UIWINDOWIMG_UTILDLGEX | UI/UIWindow.img/UtilDlgEx |
| 2253 | SP_2253_BORDER_0 | Border/0 |
| 2254 | SP_2254_BORDER_1 | Border/1 |
| 2255 | SP_2255_BORDER_2 | Border/2 |
| 2256 | SP_2256_BORDER_3 | Border/3 |
| 2257 | SP_2257_BORDER_4 | Border/4 |
| 2258 | SP_2258_BORDER_5 | Border/5 |
| 2259 | SP_2259_BORDER_6 | Border/6 |
| 2260 | SP_2260_BORDER_7 | Border/7 |
| 2261 | SP_2261_BTPREV | BtPrev |
| 2262 | SP_2262_BTNEXT | BtNext |
| 2263 | SP_2263_BTOK | BtOK |
| 2264 | SP_2264_BTCANCEL | BtCancel |
| 2265 | SP_2265_BTCLOSE | BtClose |
| 2266 | SP_2266_BTYES | BtYes |
| 2267 | SP_2267_BTNO | BtNo |
| 2268 | SP_2268_UI_UIWINDOWIMG_UTILDLGEXAVATAR_BTPREV | UI/UIWindow.img/UtilDlgEx_Avatar/BtPrev |
| 2269 | SP_2269_UI_UIWINDOWIMG_UTILDLGEXAVATAR_BTNEXT | UI/UIWindow.img/UtilDlgEx_Avatar/BtNext |
| 2270 | SP_2270_UI_UIWINDOWIMG_UTILDLGEXAVATAR_NAMETAG | UI/UIWindow.img/UtilDlgEx_Avatar/nameTag |
| 2271 | SP_2271_UI_UIWINDOWIMG_UTILDLGEXAVATAR_BTOFF | UI/UIWindow.img/UtilDlgEx_Avatar/BtOff |
| 2272 | SP_2272_UI_UIWINDOWIMG_UTILDLGEXAVATAR_BTON | UI/UIWindow.img/UtilDlgEx_Avatar/BtOn |
| 2273 | SP_2273_JUMP | Jump |
| 2274 | SP_2274_NOT_A_SERIALIZABLE_OBJECT | Not a serializable object |
| 2275 | SP_2275_NOT_AN_ENUMERATABLE_OBJECT | Not an enumeratable object |
| 2276 | SP_2276_MAP_WORLDMAP_SIMG | Map/WorldMap/%s.img |
| 2277 | SP_2277_MAP_MAPHELPERIMG_WORLDMAP_MAPIMAGE | Map/MapHelper.img/worldMap/mapImage |
| 2278 | SP_2278_MAP_MAPHELPERIMG_WORLDMAP_CURPOS | Map/MapHelper.img/worldMap/curPos |
| 2279 | SP_2279_MAP_MAPHELPERIMG_WORLDMAP_PARTYPOS | Map/MapHelper.img/worldMap/partyPos |
| 2280 | SP_2280_MAP_MAPHELPERIMG_WORLDMAP_LOVEPOS | Map/MapHelper.img/worldMap/lovePos |
| 2281 | SP_2281_UI_UIWINDOWIMG_WORLDMAP_BORDER_7 | UI/UIWindow.img/WorldMap/Border/7 |
| 2282 | SP_2282_UI_UIWINDOWIMG_WORLDMAP_BORDER_6 | UI/UIWindow.img/WorldMap/Border/6 |
| 2283 | SP_2283_UI_UIWINDOWIMG_WORLDMAP_BORDER_5 | UI/UIWindow.img/WorldMap/Border/5 |
| 2284 | SP_2284_UI_UIWINDOWIMG_WORLDMAP_BORDER_4 | UI/UIWindow.img/WorldMap/Border/4 |
| 2285 | SP_2285_UI_UIWINDOWIMG_WORLDMAP_BORDER_3 | UI/UIWindow.img/WorldMap/Border/3 |
| 2286 | SP_2286_UI_UIWINDOWIMG_WORLDMAP_BORDER_2 | UI/UIWindow.img/WorldMap/Border/2 |
| 2287 | SP_2287_UI_UIWINDOWIMG_WORLDMAP_BORDER_1 | UI/UIWindow.img/WorldMap/Border/1 |
| 2288 | SP_2288_UI_UIWINDOWIMG_WORLDMAP_BORDER_0 | UI/UIWindow.img/WorldMap/Border/0 |
| 2289 | SP_2289_UI_UIWINDOW_WORLDMAP_TITLE | UI/UIWindow/WorldMap/title |
| 2290 | SP_2290_BASEIMG_0 | BaseImg/0 |
| 2291 | SP_2291_MAPLIST | MapList |
| 2292 | SP_2292_MAPNO | mapNo |
| 2293 | SP_2293_MAPDESC | mapDesc |
| 2294 | SP_2294__DATA | ../Data |
| 2295 | SP_2295__DATAT | ../DataT |
| 2296 | SP_2296_NAMESPACEPACKAGE | NameSpace#Package |
| 2297 | SP_2297_DATAWZ | Data.wz |
| 2298 | SP_2298_GR2DDX8 | Gr2D_DX8 |
| 2299 | SP_2299_UI_BASICIMG | UI/Basic.img |
| 2300 | SP_2300_CURSOR | Cursor |
| 2301 | SP_2301_FAILED_IN_CREATING_SECURITY_THREAD | Failed in creating security thread |
| 2302 | SP_2302_MENUUP | MenuUp |
| 2303 | SP_2303_MENUDOWN | MenuDown |
| 2304 | SP_2304_EAT | eat |
| 2305 | SP_2305_USESHOPITEM | UseShopItem |
| 2306 | SP_2306_SPEC | spec |
| 2307 | SP_2307_MOVETO | moveTo |
| 2308 | SP_2308_RETURNMAPQR | returnMapQR |
| 2309 | SP_2309_IGNORECONTINENT | ignoreContinent |
| 2310 | SP_2310_EFFECT_BASICEFFIMG_LEVELUP | Effect/BasicEff.img/LevelUp |
| 2311 | SP_2311_EFFECT_BASICEFFIMG_MONSTERBOOK_CARDGET | Effect/BasicEff.img/MonsterBook/cardGet |
| 2312 | SP_2312_EFFECT_BASICEFFIMG_JOBCHANGED | Effect/BasicEff.img/JobChanged |
| 2313 | SP_2313_EFFECT_BASICEFFIMG_QUESTCLEAR | Effect/BasicEff.img/QuestClear |
| 2314 | SP_2314_EFFECT_BASICEFFIMG_ITEMLEVELUP | Effect/BasicEff.img/ItemLevelUp |
| 2315 | SP_2315_EFFECT_BASICEFFIMG_INCEXP | Effect/BasicEff.img/IncEXP |
| 2316 | SP_2316_JOBCHANGED | JobChanged |
| 2317 | SP_2317_QUESTCLEAR | QuestClear |
| 2318 | SP_2318_ITEMLEVELUP | ItemLevelUp |
| 2319 | SP_2319_INCEXP | IncEXP |
| 2320 | SP_2320_X1 | x1 |
| 2321 | SP_2321_X2 | x2 |
| 2322 | SP_2322_Y1 | y1 |
| 2323 | SP_2323_Y2 | y2 |
| 2324 | SP_2324_L | l |
| 2325 | SP_2325_UF | uf |
| 2326 | SP_2326__ | /? |
| 2327 | SP_2327_CHARACTER_08DIMG | Character/%08d.img |
| 2328 | SP_2328_CHARACTER_FACE_08DIMG | Character/Face/%08d.img |
| 2329 | SP_2329_CHARACTER_HAIR_08DIMG | Character/Hair/%08d.img |
| 2330 | SP_2330_CHARACTER_CAP_08DIMG | Character/Cap/%08d.img |
| 2331 | SP_2331_CHARACTER_ACCESSORY_08DIMG | Character/Accessory/%08d.img |
| 2332 | SP_2332_CHARACTER_COAT_08DIMG | Character/Coat/%08d.img |
| 2333 | SP_2333_CHARACTER_LONGCOAT_08DIMG | Character/Longcoat/%08d.img |
| 2334 | SP_2334_CHARACTER_PANTS_08DIMG | Character/Pants/%08d.img |
| 2335 | SP_2335_CHARACTER_SHOES_08DIMG | Character/Shoes/%08d.img |
| 2336 | SP_2336_CHARACTER_GLOVE_08DIMG | Character/Glove/%08d.img |
| 2337 | SP_2337_CHARACTER_SHIELD_08DIMG | Character/Shield/%08d.img |
| 2338 | SP_2338_CHARACTER_CAPE_08DIMG | Character/Cape/%08d.img |
| 2339 | SP_2339_CHARACTER_RING_08DIMG | Character/Ring/%08d.img |
| 2340 | SP_2340_CHARACTER_PETEQUIP_08DIMG | Character/PetEquip/%08d.img |
| 2341 | SP_2341_CHARACTER_WEAPON_08DIMG | Character/Weapon/%08d.img |
| 2342 | SP_2342_CHARACTER_TAMINGMOB_08DIMG | Character/TamingMob/%08d.img |
| 2343 | SP_2343_CHARACTER_SUB_08DIMG | Character/Sub/%08d.img |
| 2344 | SP_2344_CHARACTER_DRAGON_08DIMG | Character/Dragon/%08d.img |
| 2345 | SP_2345_S_WEEKLY_D | %s/weekly/%d |
| 2346 | SP_2346_STRING_ITEMIMG | String/Item.img |
| 2347 | SP_2347_STRING_MAPIMG | String/Map.img |
| 2348 | SP_2348_CONSUME | Consume |
| 2349 | SP_2349_INSTALL | Install |
| 2350 | SP_2350_ITEM_INSTALL_0301IMG_08D_EFFECT | Item/Install/0301.img/%08d/effect |
| 2351 | SP_2351_ITEM_INSTALL_0301IMG_08D_EFFECT2 | Item/Install/0301.img/%08d/effect2 |
| 2352 | SP_2352_ITEM_S_04DIMG | Item/%s/%04d.img |
| 2353 | SP_2353_ITEM_CASH_04DIMG | Item/Cash/%04d.img |
| 2354 | SP_2354_ITEM_CASH_04DIMG_08D_HIT | Item/Cash/%04d.img/%08d/hit |
| 2355 | SP_2355_ICOND | iconD |
| 2356 | SP_2356_ICON | icon |
| 2357 | SP_2357_ICONRAWD | iconRawD |
| 2358 | SP_2358_QUICKSLOT | quickSlot |
| 2359 | SP_2359_UNITPRICE | unitPrice |
| 2360 | SP_2360_UI_CASHSHOPIMG_CASHITEM_0 | UI/CashShop.img/CashItem/0 |
| 2361 | SP_2361_UI_CASHSHOPIMG_PREPAIDCASHITEM_0 | UI/CashShop.img/PrepaidCashItem/0 |
| 2362 | SP_2362_EFFECT_SETEFFIMG | Effect/SetEff.img |
| 2363 | SP_2363_OVER | over |
| 2364 | SP_2364_TIME | time |
| 2365 | SP_2365_PROP | prop |
| 2366 | SP_2366_ID | id |
| 2367 | SP_2367_ACQUIRE | acquire |
| 2368 | SP_2368_ITEM_S | Item/%s |
| 2369 | SP_2369_SLOTMAX | slotMax |
| 2370 | SP_2370_NO_CRC_INFO | No CRC info |
| 2371 | SP_2371_CRC_INFO_IS_OUT_OF_DATA | CRC info is out-of-data |
| 2372 | SP_2372_SKILLTYPE | skillType |
| 2373 | SP_2373_WEAPON | weapon |
| 2374 | SP_2374_AFFECTED | affected |
| 2375 | SP_2375_EFFECTC | effectC |
| 2376 | SP_2376_SKILL_03DIMG_SKILL_07D_EFFECT | Skill/%03d.img/skill/%07d/effect |
| 2377 | SP_2377_SKILL_03DIMG_SKILL_07D_SCREEN | Skill/%03d.img/skill/%07d/screen |
| 2378 | SP_2378_SKILL_03DIMG_SKILL_07D_REPEAT | Skill/%03d.img/skill/%07d/repeat |
| 2379 | SP_2379_SKILL_03DIMG_SKILL_07D_LEVEL_EFFECT | Skill/%03d.img/skill/%07d/level/effect |
| 2380 | SP_2380_SKILL_03DIMG_SKILL_07D_AFFECTED | Skill/%03d.img/skill/%07d/affected |
| 2381 | SP_2381_SKILL_03DIMG_SKILL_07D_LEVEL_AFFECTED | Skill/%03d.img/skill/%07d/level/affected |
| 2382 | SP_2382_SKILL_03DIMG_SKILL_07D_HIT | Skill/%03d.img/skill/%07d/hit |
| 2383 | SP_2383_SKILL_03DIMG_SKILL_07D_HIT_D | Skill/%03d.img/skill/%07d/hit/%d |
| 2384 | SP_2384_SKILL_03DIMG_SKILL_07D_LEVEL_D_HIT_D | Skill/%03d.img/skill/%07d/level/%d/hit/%d |
| 2385 | SP_2385_SKILL_03DIMG_SKILL_07D_BALL | Skill/%03d.img/skill/%07d/ball |
| 2386 | SP_2386_SKILL_03DIMG_SKILL_07D_LEVEL_D_BALL | Skill/%03d.img/skill/%07d/level/%d/ball |
| 2387 | SP_2387_SKILL_03DIMG_SKILL_07D_MOB | Skill/%03d.img/skill/%07d/mob |
| 2388 | SP_2388_SKILL_03DIMG_SKILL_07D_LEVEL_MOB | Skill/%03d.img/skill/%07d/level/mob |
| 2389 | SP_2389_SKILL_03DIMG_SKILL_07D_TILE | Skill/%03d.img/skill/%07d/tile |
| 2390 | SP_2390_SKILL_03DIMG_SKILL_07D_LEVEL_TILE | Skill/%03d.img/skill/%07d/level/tile |
| 2391 | SP_2391_SKILL_03DIMG_SKILL_07D_AFTERIMAGE | Skill/%03d.img/skill/%07d/afterimage |
| 2392 | SP_2392_SKILL_03DIMG_SKILL_07D_LEVEL_AFTERIMAGE | Skill/%03d.img/skill/%07d/level/afterimage |
| 2393 | SP_2393_SKILL_03DIMG_SKILL_07D_SUMMON | Skill/%03d.img/skill/%07d/summon |
| 2394 | SP_2394_SKILL_03DIMG_SKILL_07D_LEVEL_SUMMON | Skill/%03d.img/skill/%07d/level/summon |
| 2395 | SP_2395_AFTERIMAGE | afterimage |
| 2396 | SP_2396_ICONDISABLED | iconDisabled |
| 2397 | SP_2397_ICONMOUSEOVER | iconMouseOver |
| 2398 | SP_2398_FINALATTACK | finalAttack |
| 2399 | SP_2399_HS | hs |
| 2400 | SP_2400_PSSKIN_D | PSSkin/%d |
| 2401 | SP_2401_KEYCONFIG | KeyConfig |
| 2402 | SP_2402_EQUIPS | Equip(%s) |
| 2403 | SP_2403_ITEMS | Item(%s) |
| 2404 | SP_2404_ABILITYS | Ability(%s) |
| 2405 | SP_2405_SKILLS | Skill(%s) |
| 2406 | SP_2406_UI_UIWINDOWIMG_KEYCONFIG | UI/UIWindow.img/KeyConfig |
| 2407 | SP_2407_UI_UIWINDOWIMG_KEYCONFIG_BTCLOSE | UI/UIWindow.img/KeyConfig/BtClose |
| 2408 | SP_2408_UI_UIWINDOWIMG_KEYCONFIG_BTHELP | UI/UIWindow.img/KeyConfig/BtHelp |
| 2409 | SP_2409_UI_UIWINDOWIMG_KEYCONFIG_BTDELETE | UI/UIWindow.img/KeyConfig/BtDelete |
| 2410 | SP_2410_UI_UIWINDOWIMG_KEYCONFIG_BTQUICKSLOT | UI/UIWindow.img/KeyConfig/BtQuickSlot |
| 2411 | SP_2411_UI_UIWINDOWIMG_KEYCONFIG_QUICKSLOTCONFIG_BACKGRND | UI/UIWindow.img/KeyConfig/quickslotConfig/backgrnd |
| 2412 | SP_2412_UI_UIWINDOWIMG_KEYCONFIG_QUICKSLOTCONFIG_BTQUICKSETTING | UI/UIWindow.img/KeyConfig/quickslotConfig/BtQuickSetting |
| 2413 | SP_2413_ICON_D | icon/%d |
| 2414 | SP_2414_KEY_D | key/%d |
| 2415 | SP_2415_UI_STATUSBARIMG_GAUGE_GRADUATION | UI/StatusBar.img/gauge/graduation |
| 2416 | SP_2416_UI_STATUSBARIMG_NUMBER | UI/StatusBar.img/number |
| 2417 | SP_2417_LBRACKET | Lbracket |
| 2418 | SP_2418_RBRACKET | Rbracket |
| 2419 | SP_2419_PERCENT | percent |
| 2420 | SP_2420_SLASH | slash |
| 2421 | SP_2421_UI_UIWINDOWIMG_KEYCONFIG_QUICKSLOTCONFIG_KEY | UI/UIWindow.img/KeyConfig/quickslotConfig/key |
| 2422 | SP_2422_CHATBALLOON | chatBalloon |
| 2423 | SP_2423_NAMETAG | nameTag |
| 2424 | SP_2424_MEDALTAG | medalTag |
| 2425 | SP_2425_TOMBSTONE | Tombstone |
| 2426 | SP_2426_EFFECT_TOMBIMG_LAND | Effect/Tomb.img/land |
| 2427 | SP_2427_EFFECT_TOMBUPGRADEIMG_EFFECT | Effect/TombUpgrade.img/effect |
| 2428 | SP_2428_SKILL_3DIMG_SKILL_D_FINISH_D | Skill/%3d.img/skill/%d/finish/%d |
| 2429 | SP_2429_SKILL_03DIMG_SKILL_07D_FINISH | Skill/%03d.img/skill/%07d/finish |
| 2430 | SP_2430_EFFECT_SKILLNAMEDIMG_07D_0 | Effect/SkillName%d.img/%07d/0 |
| 2431 | SP_2431_CHARACTER_AFTERIMAGE_ | Character/Afterimage/ |
| 2432 | SP_2432_EFFECT_SETEFFIMG_D_EFFECT | Effect/SetEff.img/%d/effect |
| 2433 | SP_2433_EFFECT_ITEMEFFIMG_D_0 | Effect/ItemEff.img/%d/0 |
| 2434 | SP_2434_EFFECT_ITEMEFFIMG_D_1 | Effect/ItemEff.img/%d/1 |
| 2435 | SP_2435_EFFECT_ITEMEFFIMG_D | Effect/ItemEff.img/%d |
| 2436 | SP_2436_SKILL_111IMG_SKILL_1111002_STATE_D | Skill/111.img/skill/1111002/state/%d |
| 2437 | SP_2437_SKILL_111IMG_SKILL_1111002_STATE_0 | Skill/111.img/skill/1111002/state/0 |
| 2438 | SP_2438_SKILL_112IMG_SKILL_1120003_STATE_D | Skill/112.img/skill/1120003/state/%d |
| 2439 | SP_2439_SKILL_112IMG_SKILL_1120003_STATE_0 | Skill/112.img/skill/1120003/state/0 |
| 2440 | SP_2440_SICHARACTERNAME | siCharacterName |
| 2441 | SP_2441_SIWORLDID | siWorldID |
| 2442 | SP_2442_SICHANNELID | siChannelID |
| 2443 | SP_2443_RMA | RMA |
| 2444 | SP_2444_LMA | LMA |
| 2445 | SP_2445_LCWN | LCWN |
| 2446 | SP_2446_SCRRRATE | scrRRate |
| 2447 | SP_2447_SCRFIRSTRUN | scrFirstRun |
| 2448 | SP_2448_SOVIDEO | soVideo |
| 2449 | SP_2449_SOBGMVOL | soBGMVol |
| 2450 | SP_2450_SOBGMMUTE | soBGMMute |
| 2451 | SP_2451_SOSEVOL | soSEVol |
| 2452 | SP_2452_SOSEMUTE | soSEMute |
| 2453 | SP_2453_SOSCREENSHOT | soScreenShot |
| 2454 | SP_2454_SOMOUSESPEED | soMouseSpeed |
| 2455 | SP_2455_SOMOBINFO | soMobInfo |
| 2456 | SP_2456_SOFULLSCREEN | soFullScreen |
| 2457 | SP_2457_UIWNDXD | uiWndX%d |
| 2458 | SP_2458_UIWNDYD | uiWndY%d |
| 2459 | SP_2459_UIOPTD | uiOpt%d |
| 2460 | SP_2460_S_GDD08X | %s\\g%d_%d_%08X |
| 2461 | SP_2461_S_L08X | %s\\l_%08X |
| 2462 | SP_2462_FUNCKEYMAPPEDTYPED | funcKeyMapped_Type%d |
| 2463 | SP_2463_FUNCKEYMAPPEDIDD | funcKeyMapped_ID%d |
| 2464 | SP_2464_GOMINIMAP | goMiniMap |
| 2465 | SP_2465_GOWHISPER | goWhisper |
| 2466 | SP_2466_GOFRIEND | goFriend |
| 2467 | SP_2467_GOMESSENGER | goMessenger |
| 2468 | SP_2468_GOEXCHANGE | goExchange |
| 2469 | SP_2469_GOPARTY | goParty |
| 2470 | SP_2470_GOGUILDINVITE | goGuildINVITE |
| 2471 | SP_2471_GOGUILDTALK | goGuildTALK |
| 2472 | SP_2472_GOGAME | goGame |
| 2473 | SP_2473_GOKEYCONFIG | goKeyConfig |
| 2474 | SP_2474_GOCHATTYPE | goChatType |
| 2475 | SP_2475_GOFAMILY | goFamily |
| 2476 | SP_2476_LISTENBLOCKEDFRIENDCNT | ListenBlockedFriend_cnt |
| 2477 | SP_2477_LISTENBLOCKEDFRIENDD | ListenBlockedFriend_%d |
| 2478 | SP_2478_TALKBLOCKEDFRIENDCNT | TalkBlockedFriend_cnt |
| 2479 | SP_2479_TALKBLOCKEDFRIENDD | TalkBlockedFriend_%d |
| 2480 | SP_2480_BLACKLISTCNT | BlackList_cnt |
| 2481 | SP_2481_BLACKLISTD | BlackList_%d |
| 2482 | SP_2482_QUESTALARMD | QuestAlarm_%d |
| 2483 | SP_2483_QUESTALARMCNT | QuestAlarm_cnt |
| 2484 | SP_2484_QUESTALARMAUTOREGISTER | QuestAlarm_autoregister |
| 2485 | SP_2485_QUESTALARMOPENED | QuestAlarm_opened |
| 2486 | SP_2486_FRIENDSHOWONLINEONLY | Friend_ShowOnlineOnly |
| 2487 | SP_2487_FRIENDGROUPCNT | FriendGroup_cnt |
| 2488 | SP_2488_FRIENDGROUPD | FriendGroup_%d |
| 2489 | SP_2489_OSSYRIA | Ossyria. |
| 2490 | SP_2490_PREPARE | prepare |
| 2491 | SP_2491_SKILL_03DIMG_SKILL_07D_PREPARE | Skill/%03d.img/skill/%07d/prepare |
| 2492 | SP_2492_SPECIAL | special |
| 2493 | SP_2493_SKILL_03DIMG_SKILL_07D_SPECIAL | Skill/%03d.img/skill/%07d/special |
| 2494 | SP_2494_UI_UIWINDOWIMG_MINIGAMETABLE_EFFECT | UI/UIWindow.img/MinigameTable/Effect |
| 2495 | SP_2495_ITS_THE_ROUND_OF_N | It's the round of %n. |
| 2496 | SP_2496_ITS_THE_SEMIFINAL_MATCH | It's the semifinal match. |
| 2497 | SP_2497_ITS_THE_FINAL_MATCH | It's the final match. |
| 2498 | SP_2498_MAP_MAP_MAPD_09DIMG | Map/Map/Map%d/%09d.img |
| 2499 | SP_2499_REPEAT | repeat |
| 2500 | SP_2500_MAP_EFFECTIMG_S | Map/Effect.img/%s |
| 2501 | SP_2501_DOJANG_TIMEOVER | dojang/timeOver |
| 2502 | SP_2502_CHARACTER_AFTERIMAGE_HITIMG_ANNIVERSARY | Character/Afterimage/hit.img/anniversary |
| 2503 | SP_2503_UI_UIWINDOWIMG_KEYCONFIG_NOTICE_D | UI/UIWindow.img/KeyConfig/notice/%d |
| 2504 | SP_2504_UI_STATUSBARIMG_BASE_QUICKSLOT | UI/StatusBar.img/base/quickSlot |
| 2505 | SP_2505_ITEMS_ARE_NOT_AVAILABLE_FOR_PURCHASE_R_N_AT_THIS_HOUR | Items are not available for purchase\r\n at this hour. |
| 2506 | SP_2506_THE_ITEM_IS_OUT_OF_STOCK_AND_THEREFORE_R_NNOT_AVAILABLE_FOR_SALE | The item is out of stock, and therefore\r\nnot available for sale. |
| 2507 | SP_2507_THIS_ITEM_CANNOT_BE_MOVED | This item cannot be moved. |
| 2508 | SP_2508_UI_CASHSHOPIMG_GUIDEWORDS_D | UI/CashShop.img/GuideWords/%d |
| 2509 | SP_2509_YOU_HAVE_REACHED_THE_ROUND_OF_N_BY_DEFAULT | You have reached the round of %n by default. |
| 2510 | SP_2510_YOU_HAVE_REACHED_THE_SEMIFINALS_BY_DEFAULT | You have reached the semifinals by default. |
| 2511 | SP_2511_YOU_HAVE_REACHED_THE_FINALS_BY_DEFAULT | You have reached the finals by default. |
| 2512 | SP_2512_MSEXCRINI | msexcr.ini |
| 2513 | SP_2513_MSEXCRTINI | msexcrt.ini |
| 2514 | SP_2514_DAMAGE | Damage |
| 2515 | SP_2515_DIE | Die |
| 2516 | SP_2516_ATTACK1 | Attack1 |
| 2517 | SP_2517_ATTACK3 | Attack3 |
| 2518 | SP_2518_ATTACK4 | Attack4 |
| 2519 | SP_2519_ATTACK5 | Attack5 |
| 2520 | SP_2520_ATTACK6 | Attack6 |
| 2521 | SP_2521_ATTACK7 | Attack7 |
| 2522 | SP_2522_ATTACK8 | Attack8 |
| 2523 | SP_2523_ATTACKF | AttackF |
| 2524 | SP_2524_SKILL1 | Skill1 |
| 2525 | SP_2525_SKILL2 | Skill2 |
| 2526 | SP_2526_SKILL3 | Skill3 |
| 2527 | SP_2527_SKILL4 | Skill4 |
| 2528 | SP_2528_SKILL5 | Skill5 |
| 2529 | SP_2529_SKILL6 | Skill6 |
| 2530 | SP_2530_SKILL7 | Skill7 |
| 2531 | SP_2531_SKILL8 | Skill8 |
| 2532 | SP_2532_SKILL9 | Skill9 |
| 2533 | SP_2533_SKILL10 | Skill10 |
| 2534 | SP_2534_SKILL11 | Skill11 |
| 2535 | SP_2535_SKILL12 | Skill12 |
| 2536 | SP_2536_SKILL13 | Skill13 |
| 2537 | SP_2537_SKILL14 | Skill14 |
| 2538 | SP_2538_SKILL15 | Skill15 |
| 2539 | SP_2539_SKILL16 | Skill16 |
| 2540 | SP_2540_SKILLF | SkillF |
| 2541 | SP_2541_CHARDAM1 | CharDam1 |
| 2542 | SP_2542_CHARDAM2 | CharDam2 |
| 2543 | SP_2543_CHARDAMF | CharDamF |
| 2544 | SP_2544_BOMB | Bomb |
| 2545 | SP_2545_REGEN | Regen |
| 2546 | SP_2546_ATTACK | Attack |
| 2547 | SP_2547_SOFTWARE_WIZET_MAPLESTORY | SOFTWARE\\Wizet\\MapleStory |
| 2548 | SP_2548_SOFTWARE_WIZET_MAPLESTORYT | SOFTWARE\\Wizet\\MapleStoryT |
| 2549 | SP_2549_SKO | SKO |
| 2550 | SP_2550_THIS_CLIENT_CAN_BE_EXECUTED_ONLY_IN_KOREAN_CODE_PAGE | This client can be executed only in Korean code-page |
| 2551 | SP_2551_FAILED_IN_INITIALIZING_COM | Failed in initializing COM |
| 2552 | SP_2552_UI_UIWINDOWIMG_CHANNEL_WORLD_D | UI/UIWindow.img/Channel/world/%d |
| 2553 | SP_2553_UI_UIWINDOWIMG_CHANNEL_WORLD_TD | UI/UIWindow.img/Channel/world/t%d |
| 2554 | SP_2554_CHANNEL0 | channel0 |
| 2555 | SP_2555_CHANNEL1 | channel1 |
| 2556 | SP_2556_CHANNEL2 | channel2 |
| 2557 | SP_2557_SOUND_FIELDIMG_S | Sound/Field.img/%s |
| 2558 | SP_2558_DOJANG_TIMEOVER | Dojang/timeOver |
| 2559 | SP_2559_SOUND_CASHEFFECTIMG_S | Sound/CashEffect.img/%s |
| 2560 | SP_2560_UI_UIWINDOWIMG_CHANNEL_CH_D | UI/UIWindow.img/Channel/ch/%d |
| 2561 | SP_2561_UI_BASICIMG_BTHIDE | UI/Basic.img/BtHide |
| 2562 | SP_2562_UI_BASICIMG_BTHIDE2 | UI/Basic.img/BtHide2 |
| 2563 | SP_2563_UI_BASICIMG_BTHIDE3 | UI/Basic.img/BtHide3 |
| 2564 | SP_2564_UI_UIWINDOWIMG_USERINFO_REQLV | UI/UIWindow.img/UserInfo/ReqLv |
| 2565 | SP_2565_UI_UIWINDOWIMG_USERINFO_BOTTOM | UI/UIWindow.img/UserInfo/bottom |
| 2566 | SP_2566_UI_UIWINDOWIMG_USERINFO_BTPETHIDE | UI/UIWindow.img/UserInfo/BtPetHide |
| 2567 | SP_2567_UI_UIWINDOWIMG_USERINFO_BTPETSHOW | UI/UIWindow.img/UserInfo/BtPetShow |
| 2568 | SP_2568_UI_UIWINDOWIMG_USERINFO_BTTAMINGHIDE | UI/UIWindow.img/UserInfo/BtTamingHide |
| 2569 | SP_2569_UI_UIWINDOWIMG_USERINFO_BTTAMINGSHOW | UI/UIWindow.img/UserInfo/BtTamingShow |
| 2570 | SP_2570_UI_UIWINDOWIMG_USERINFO_BTBOOKHIDE | UI/UIWindow.img/UserInfo/BtBookHide |
| 2571 | SP_2571_UI_UIWINDOWIMG_USERINFO_BTBOOKSHOW | UI/UIWindow.img/UserInfo/BtBookShow |
| 2572 | SP_2572_UI_UIWINDOWIMG_USERINFO_BTITEM | UI/UIWindow.img/UserInfo/BtItem |
| 2573 | SP_2573_UI_UIWINDOWIMG_USERINFO_BTWISH | UI/UIWindow.img/UserInfo/BtWish |
| 2574 | SP_2574_UI_UIWINDOWIMG_USERINFO_BTFAMILY | UI/UIWindow.img/UserInfo/BtFamily |
| 2575 | SP_2575_UI_UIWINDOWIMG_USERINFO_BTPARTY | UI/UIWindow.img/UserInfo/BtParty |
| 2576 | SP_2576_UI_UIWINDOWIMG_USERINFO_BTTRADE | UI/UIWindow.img/UserInfo/BtTrade |
| 2577 | SP_2577_UI_UIWINDOWIMG_USERINFO_MARRY_MARRIED_0 | UI/UIWindow.img/UserInfo/Marry/married/0 |
| 2578 | SP_2578_UI_UIWINDOWIMG_USERINFO_MARRY_UNMARRIED_0 | UI/UIWindow.img/UserInfo/Marry/unmarried/0 |
| 2579 | SP_2579_UI_UIWINDOWIMG_USERINFO_BTEXCEPTIONSHOW | UI/UIWindow.img/UserInfo/BtExceptionShow |
| 2580 | SP_2580_UI_UIWINDOWIMG_USERINFO_BTEXCEPTIONHIDE | UI/UIWindow.img/UserInfo/BtExceptionHide |
| 2581 | SP_2581_UI_UIWINDOWIMG_USERINFO_BTMESO | UI/UIWindow.img/UserInfo/BtMeso |
| 2582 | SP_2582_UI_UIWINDOWIMG_USERINFO_BTREGISTER | UI/UIWindow.img/UserInfo/BtRegister |
| 2583 | SP_2583_UI_UIWINDOWIMG_USERINFO_BTDELETE | UI/UIWindow.img/UserInfo/BtDelete |
| 2584 | SP_2584_UI_UIWINDOWIMG_MONSTERBOOK | UI/UIWindow.img/MonsterBook |
| 2585 | SP_2585_UI_UIWINDOWIMG_MONSTERBOOK_BTCLOSE | UI/UIWindow.img/MonsterBook/BtClose |
| 2586 | SP_2586_UI_UIWINDOWIMG_MONSTERBOOK_BTSEARCH | UI/UIWindow.img/MonsterBook/BtSearch |
| 2587 | SP_2587_UI_UIWINDOWIMG_MONSTERBOOK_ARROWLEFT | UI/UIWindow.img/MonsterBook/arrowLeft |
| 2588 | SP_2588_UI_UIWINDOWIMG_MONSTERBOOK_ARROWRIGHT | UI/UIWindow.img/MonsterBook/arrowRight |
| 2589 | SP_2589_UI_UIWINDOWIMG_MONSTERBOOK_LEFTTAB | UI/UIWindow.img/MonsterBook/LeftTab |
| 2590 | SP_2590_UI_UIWINDOWIMG_MONSTERBOOK_LEFTTABINFO | UI/UIWindow.img/MonsterBook/LeftTabInfo |
| 2591 | SP_2591_UI_UIWINDOWIMG_MONSTERBOOK_RIGHTTAB | UI/UIWindow.img/MonsterBook/RightTab |
| 2592 | SP_2592_UI_UIWINDOWIMG_MONSTERBOOK_INFOPAGE | UI/UIWindow.img/MonsterBook/infoPage |
| 2593 | SP_2593_UI_UIWINDOWIMG_MONSTERBOOK_CARDSLOT | UI/UIWindow.img/MonsterBook/cardSlot |
| 2594 | SP_2594_MONSTERBOOK | MonsterBook |
| 2595 | SP_2595_MONSTERBOOK | monsterBook |
| 2596 | SP_2596__S__HAS_BEEN_SUCCESSFULLY_RECORDED_ON_THE_MONSTER_BOOK | [%s] has been successfully recorded on the Monster Book. |
| 2597 | SP_2597_THIS_CARD_IS_ALREADY_FULL_IN_THE_MONSTER_BOOK_THIS_CARD_WILL_DISAPPEAR | This card is already full in the Monster Book. This card will disappear. |
| 2598 | SP_2598_UI_BASICIMG_BTUP | UI/Basic.img/BtUP |
| 2599 | SP_2599_UI_BASICIMG_BTDOWN | UI/Basic.img/BtDown |
| 2600 | SP_2600_UI_UIWINDOWIMG_CONTEXTMENU_BACKGRND | UI/UIWindow.img/ContextMenu/backgrnd |
| 2601 | SP_2601_UI_UIWINDOWIMG_CONTEXTMENU_BTINFO | UI/UIWindow.img/ContextMenu/BtInfo |
| 2602 | SP_2602_UI_UIWINDOWIMG_CONTEXTMENU_BTSAVE | UI/UIWindow.img/ContextMenu/BtSave |
| 2603 | SP_2603_UI_UIWINDOWIMG_CONTEXTMENU_BTWHISPER | UI/UIWindow.img/ContextMenu/BtWhisper |
| 2604 | SP_2604_UI_UIWINDOWIMG_CONTEXTMENU_BTCLAIM | UI/UIWindow.img/ContextMenu/BtClaim |
| 2605 | SP_2605_UI_UIWINDOWIMG_CONTEXTMENU_T | UI/UIWindow.img/ContextMenu/t |
| 2606 | SP_2606_UI_UIWINDOWIMG_CONTEXTMENU_C | UI/UIWindow.img/ContextMenu/c |
| 2607 | SP_2607_UI_UIWINDOWIMG_CONTEXTMENU_S | UI/UIWindow.img/ContextMenu/s |
| 2608 | SP_2608_UI_UIWINDOWIMG_CONTEXTMENU_BTPARTY | UI/UIWindow.img/ContextMenu/BtParty |
| 2609 | SP_2609_UI_UIWINDOWIMG_CONTEXTMENU_BTGUILD | UI/UIWindow.img/ContextMenu/BtGuild |
| 2610 | SP_2610_UI_UIWINDOWIMG_CONTEXTMENU_BTTRADE | UI/UIWindow.img/ContextMenu/BtTrade |
| 2611 | SP_2611_UI_UIWINDOWIMG_CONTEXTMENU_BTFAMILY | UI/UIWindow.img/ContextMenu/BtFamily |
| 2612 | SP_2612_UI_UIWINDOWIMG_PERSONALSHOP_SELECT | UI/UIWindow.img/PersonalShop/select |
| 2613 | SP_2613_UI_UIWINDOWIMG_PERSONALSHOP_BTBUY | UI/UIWindow.img/PersonalShop/BtBuy |
| 2614 | SP_2614_UI_UIWINDOWIMG_PERSONALSHOP_BTCLOSE | UI/UIWindow.img/PersonalShop/BtClose |
| 2615 | SP_2615_UI_UIWINDOWIMG_PERSONALSHOP_BTEXIT | UI/UIWindow.img/PersonalShop/BtExit |
| 2616 | SP_2616_UI_UIWINDOWIMG_PERSONALSHOP_BTINFO | UI/UIWindow.img/PersonalShop/BtInfo |
| 2617 | SP_2617_UI_UIWINDOWIMG_PERSONALSHOP_BTITEM | UI/UIWindow.img/PersonalShop/BtItem |
| 2618 | SP_2618_UI_UIWINDOWIMG_PERSONALSHOP_BTENTER | UI/UIWindow.img/PersonalShop/BtEnter |
| 2619 | SP_2619_UI_UIWINDOWIMG_PERSONALSHOP_BAR | UI/UIWindow.img/PersonalShop/bar |
| 2620 | SP_2620_UI_UIWINDOWIMG_PERSONALSHOP_BACKGRND3 | UI/UIWindow.img/PersonalShop/backgrnd3 |
| 2621 | SP_2621_UI_UIWINDOWIMG_ENTRUSTEDSHOP_BTARRANGE | UI/UIWindow.img/EntrustedShop/BtArrange |
| 2622 | SP_2622_UI_UIWINDOWIMG_ENTRUSTEDSHOP_BTCOIN | UI/UIWindow.img/EntrustedShop/BtCoin |
| 2623 | SP_2623_MAXMAP | MaxMap |
| 2624 | SP_2624_MINMAP | MinMap |
| 2625 | SP_2625_BUYER__S | Buyer : %s |
| 2626 | SP_2626_NUMBER_PURCHASED__D | Number purchased : %d |
| 2627 | SP_2627_TOTAL_SALES___TOTAL_RECEIVED | Total Sales / Total Received |
| 2628 | SP_2628_UI_UIWINDOWIMG_MEMO_BACKGRND | UI/UIWindow.img/Memo/backgrnd |
| 2629 | SP_2629_UI_UIWINDOWIMG_MEMO_BACKGRND3 | UI/UIWindow.img/Memo/backgrnd3 |
| 2630 | SP_2630_UI_UIWINDOWIMG_MEMO | UI/UIWindow.img/Memo |
| 2631 | SP_2631_INNERTOP | innerTop |
| 2632 | SP_2632_INNERCENTER | innerCenter |
| 2633 | SP_2633_INNERBOTTOM | innerBottom |
| 2634 | SP_2634_LINE | line |
| 2635 | SP_2635_D02D02D_02D02D | %d.%02d.%02d %02d:%02d |
| 2636 | SP_2636_UI_UIWINDOWIMG_MEMO_BTSAVE | UI/UIWindow.img/Memo/BtSave |
| 2637 | SP_2637_UI_UIWINDOWIMG_MEMO_BTDELETE | UI/UIWindow.img/Memo/BtDelete |
| 2638 | SP_2638_UI_UIWINDOWIMG_MEMO_BTOPEN | UI/UIWindow.img/Memo/BtOpen |
| 2639 | SP_2639_ALL_UNSAVED_NOTES_WILL_BE_DELETED_R_NARE_YOU_SURE_YOU_WANT_TO_MOVE_ON | All unsaved notes will be deleted.\r\nAre you sure you want to move on? |
| 2640 | SP_2640_NOTES_ARRIVED | Note(s) arrived. |
| 2641 | SP_2641_YOU_HAVE_RECEIVED | YOU HAVE RECEIVED |
| 2642 | SP_2642__NOTES |  NOTE(S). |
| 2643 | SP_2643_UI_STATUSBARIMG_FONTMEMO | UI/StatusBar.img/FontMemo |
| 2644 | SP_2644_BOX | box |
| 2645 | SP_2645_ICONRED | iconRed |
| 2646 | SP_2646_ICONBLUE | iconBlue |
| 2647 | SP_2647_ICONMEMO | iconMemo |
| 2648 | SP_2648_CHECK0 | check0 |
| 2649 | SP_2649_CHECK1 | check1 |
| 2650 | SP_2650_FONTTIME | FontTime |
| 2651 | SP_2651_THE_OTHER_CHARACTER_IS_ONLINE_NOW_R_NPLEASE_USE_THE_WHISPER_FUNCTION | The other character is online now.\r\nPlease use the whisper function, |
| 2652 | SP_2652_PLEASE_CHECK_THE_NAME_OF_THE_RECEIVING_CHARACTER | Please check the name of the receiving character. |
| 2653 | SP_2653_THE_RECEIVERS_INBOX_IS_FULL_R_NPLEASE_TRY_AGAIN | The receiver's inbox is full.\r\nPlease try again. |
| 2654 | SP_2654_THE_NOTES_CAN_BE_SENT_IN_100_CHARACTERS | The notes can be sent in 100 characters. |
| 2655 | SP_2655_UI_BASICIMG_TAB | UI/Basic.img/Tab |
| 2656 | SP_2656__LEFTD | /left%d |
| 2657 | SP_2657__MIDDLED | /middle%d |
| 2658 | SP_2658__RIGHTD | /right%d |
| 2659 | SP_2659__FILLD | /fill%d |
| 2660 | SP_2660_UI_UIWINDOWIMG_SKILL_LINE | UI/UIWindow.img/Skill/line |
| 2661 | SP_2661_UI_UIWINDOWIMG_SKILLEX_LINE | UI/UIWindow.img/SkillEx/line |
| 2662 | SP_2662_UI_UIWINDOWIMG_ITEM_TAB_ENABLED | UI/UIWindow.img/Item/Tab/enabled |
| 2663 | SP_2663_UI_UIWINDOWIMG_ITEM_TAB_DISABLED | UI/UIWindow.img/Item/Tab/disabled |
| 2664 | SP_2664_UI_UIWINDOWIMG_QUEST_TAB_ENABLED | UI/UIWindow.img/Quest/Tab/enabled |
| 2665 | SP_2665_UI_UIWINDOWIMG_QUEST_TAB_DISABLED | UI/UIWindow.img/Quest/Tab/disabled |
| 2666 | SP_2666_UI_UIWINDOWIMG_SKILL_TAB_ENABLED | UI/UIWindow.img/Skill/Tab/enabled |
| 2667 | SP_2667_UI_UIWINDOWIMG_SKILL_TAB_DISABLED | UI/UIWindow.img/Skill/Tab/disabled |
| 2668 | SP_2668_UI_UIWINDOWIMG_SKILLEX_TAB_ENABLED | UI/UIWindow.img/SkillEx/Tab/enabled |
| 2669 | SP_2669_UI_UIWINDOWIMG_SKILLEX_TAB_DISABLED | UI/UIWindow.img/SkillEx/Tab/disabled |
| 2670 | SP_2670_UI_UIWINDOWIMG_USERLIST_TAB_ENABLED | UI/UIWindow.img/UserList/Tab/enabled |
| 2671 | SP_2671_UI_UIWINDOWIMG_USERLIST_TAB_DISABLED | UI/UIWindow.img/UserList/Tab/disabled |
| 2672 | SP_2672_UI_UIWINDOWIMG_USERLIST_GUILD_TAB_ENABLED | UI/UIWindow.img/UserList/Guild/Tab/enabled |
| 2673 | SP_2673_UI_UIWINDOWIMG_USERLIST_GUILD_TAB_DISABLED | UI/UIWindow.img/UserList/Guild/Tab/disabled |
| 2674 | SP_2674_UI_UIWINDOWIMG_GUILD_TAB_ENABLED | UI/UIWindow.img/Guild/Tab/enabled |
| 2675 | SP_2675_UI_UIWINDOWIMG_GUILD_TAB_DISABLED | UI/UIWindow.img/Guild/Tab/disabled |
| 2676 | SP_2676_UI_UIWINDOWIMG_SHOP_TABSELL_ENABLED | UI/UIWindow.img/Shop/TabSell/enabled |
| 2677 | SP_2677_UI_UIWINDOWIMG_SHOP_TABSELL_DISABLED | UI/UIWindow.img/Shop/TabSell/disabled |
| 2678 | SP_2678_UI_UIWINDOWIMG_SHOP_TABBUY_ENABLED | UI/UIWindow.img/Shop/TabBuy/enabled |
| 2679 | SP_2679_UI_UIWINDOWIMG_SHOP_TABBUY_DISABLED | UI/UIWindow.img/Shop/TabBuy/disabled |
| 2680 | SP_2680_UI_UIWINDOWIMG_RANKING_TAB_ENABLED | UI/UIWindow.img/Ranking/Tab/enabled |
| 2681 | SP_2681_UI_UIWINDOWIMG_RANKING_TAB_DISABLED | UI/UIWindow.img/Ranking/Tab/disabled |
| 2682 | SP_2682_UI_UIWINDOWIMG_RANKING_BACKGRND | UI/UIWindow.img/Ranking/backgrnd |
| 2683 | SP_2683_HTTP__SNEXONCOM_MAPLESTORY_PAGE_GNXGAMEASPXURLWEBCLIENT_TOTPERSONRANKWORLDIDDCHA | http://%s.nexon.com/maplestory/page/Gnxgame.aspx?URL=webclient/totpersonrank&worldid=%d&characterid=%d |
| 2684 | SP_2684_SPACE | space |
| 2685 | SP_2685_UI_BASICIMG_NOTICE3_T | UI/Basic.img/Notice3/t |
| 2686 | SP_2686_UI_BASICIMG_NOTICE3_C | UI/Basic.img/Notice3/c |
| 2687 | SP_2687_UI_BASICIMG_NOTICE3_S | UI/Basic.img/Notice3/s |
| 2688 | SP_2688_UI_BASICIMG_NOTICE4_S | UI/Basic.img/Notice4/s |
| 2689 | SP_2689_UI_BASICIMG_NOTICE4_SLINE | UI/Basic.img/Notice4/s_line |
| 2690 | SP_2690_UI_BASICIMG_NOTICE5_S | UI/Basic.img/Notice5/s_ |
| 2691 | SP_2691_UI_BASICIMG_NOTICE5_SDELGROUP | UI/Basic.img/Notice5/s_DelGroup |
| 2692 | SP_2692_UI_BASICIMG_YESNO3_T | UI/Basic.img/YesNo3/t |
| 2693 | SP_2693_UI_BASICIMG_YESNO3_C | UI/Basic.img/YesNo3/c |
| 2694 | SP_2694_UI_BASICIMG_YESNO3_S | UI/Basic.img/YesNo3/s |
| 2695 | SP_2695_D_NX | %d NX |
| 2696 | SP_2696_D_D_NX | %d %d NX |
| 2697 | SP_2697_UI_CASHSHOPIMG_CSNOTICE_0 | UI/CashShop.img/CSNotice/0 |
| 2698 | SP_2698_POS | pos |
| 2699 | SP_2699_TOWN | town |
| 2700 | SP_2700_UI_UIWINDOWIMG_USERINFO_BTPRESENT | UI/UIWindow.img/UserInfo/BtPresent |
| 2701 | SP_2701_UI_UIWINDOWIMG_UTILDLGEX_T | UI/UIWindow.img/UtilDlgEx/t |
| 2702 | SP_2702_UI_UIWINDOWIMG_UTILDLGEX_C | UI/UIWindow.img/UtilDlgEx/c |
| 2703 | SP_2703_UI_UIWINDOWIMG_UTILDLGEX_S | UI/UIWindow.img/UtilDlgEx/s |
| 2704 | SP_2704_UI_UIWINDOWIMG_UTILDLGEXAVATAR_SHADOW | UI/UIWindow.img/UtilDlgEx_Avatar/shadow |
| 2705 | SP_2705_NPC_07DIMG_INFO_DEFAULT | Npc/%07d.img/info/default |
| 2706 | SP_2706_NPC_07DIMG_STAND_0 | Npc/%07d.img/stand/0 |
| 2707 | SP_2707_UI_UIWINDOWIMG_UTILDLGEX_BAR | UI/UIWindow.img/UtilDlgEx/bar |
| 2708 | SP_2708_UI_UIWINDOWIMG_UTILDLGEX_LINE | UI/UIWindow.img/UtilDlgEx/line |
| 2709 | SP_2709_ETC_COMMODITYIMG | Etc/Commodity.img |
| 2710 | SP_2710_THERES_NO_PLATFORM_HERE | There's no platform here. |
| 2711 | SP_2711_PLEASE_ENTER_THE_NAME_R_N_OF_THE_RECEIVING_CHARACTER | Please enter the name\r\n of the receiving character. |
| 2712 | SP_2712_YOU_CANT_SEND_THE_NOTE_TO_YOURSELF | You can't send the note to yourself. |
| 2713 | SP_2713_THE_NOTE_HAS_SUCCESSFULLY_BEEN_SENT | The note has successfully been sent |
| 2714 | SP_2714_SKILL_231IMG_SKILL_2311002_MDOOR | Skill/231.img/skill/2311002/mDoor |
| 2715 | SP_2715_SKILL_231IMG_SKILL_2311002_FRAME | Skill/231.img/skill/2311002/Frame |
| 2716 | SP_2716_SKILL_231IMG_SKILL_2311002_CDOOR | Skill/231.img/skill/2311002/cDoor |
| 2717 | SP_2717_CHARACTER_AFTERIMAGE_S_CHARGE_D | Character/Afterimage/%s/charge/%d |
| 2718 | SP_2718_SINCE_THE_CHARACTER_HAS_THE_WHISPERS_BLOCKED_AT_THIS_POINT_YOU_MAY_NOT_RECEIVE_A | Since the character has the whispers blocked at this point, you may not receive an answer. |
| 2719 | SP_2719_SKILL_03DIMG_SKILL_07D_SUMMON_S_INFO_EFFECT | Skill/%03d.img/skill/%07d/summon/%s/info/effect |
| 2720 | SP_2720_SKILL_03DIMG_SKILL_07D_SUMMON_S_INFO_HIT | Skill/%03d.img/skill/%07d/summon/%s/info/hit |
| 2721 | SP_2721_SKILL_03DIMG_SKILL_07D_SUMMON_S_INFO_BALL | Skill/%03d.img/skill/%07d/summon/%s/info/ball |
| 2722 | SP_2722_UI_UIWINDOWIMG_MESSENGER_BTENTER | UI/UIWindow.img/Messenger/BtEnter |
| 2723 | SP_2723_S_IS_TAKING_CARE_OF_ANOTHER_INVITATION | '%s' is taking care of another invitation. |
| 2724 | SP_2724_UI_UIWINDOWIMG_UTILDLGEXAVATAR_BTEXIT | UI/UIWindow.img/UtilDlgEx_Avatar/BtExit |
| 2725 | SP_2725_SPELL | spell |
| 2726 | SP_2726_PERSONALSHOP | personalShop |
| 2727 | SP_2727_SAMPLE | sample |
| 2728 | SP_2728_EFFECT_BASICEFFIMG_FLYING | Effect/BasicEff.img/Flying |
| 2729 | SP_2729_EFFECT_BASICEFFIMG_FLYING1 | Effect/BasicEff.img/Flying1 |
| 2730 | SP_2730_EFFECT_BASICEFFIMG_SOULRUNNER | Effect/BasicEff.img/SoulRunner |
| 2731 | SP_2731_EFFECT_BASICEFFIMG_VERTICALJUMP | Effect/BasicEff.img/VerticalJump |
| 2732 | SP_2732_MAGIC2 | magic2 |
| 2733 | SP_2733_MAGIC3 | magic3 |
| 2734 | SP_2734_MAGIC5 | magic5 |
| 2735 | SP_2735_SKILL1 | skill1 |
| 2736 | SP_2736_SKILL2 | skill2 |
| 2737 | SP_2737_SKILL3 | skill3 |
| 2738 | SP_2738_SKILL4 | skill4 |
| 2739 | SP_2739_SKILL5 | skill5 |
| 2740 | SP_2740_SKILL6 | skill6 |
| 2741 | SP_2741_SKILL7 | skill7 |
| 2742 | SP_2742_SKILL8 | skill8 |
| 2743 | SP_2743_SKILL9 | skill9 |
| 2744 | SP_2744_SKILL10 | skill10 |
| 2745 | SP_2745_SKILL11 | skill11 |
| 2746 | SP_2746_SKILL12 | skill12 |
| 2747 | SP_2747_SKILL13 | skill13 |
| 2748 | SP_2748_SKILL14 | skill14 |
| 2749 | SP_2749_SKILL15 | skill15 |
| 2750 | SP_2750_SKILL16 | skill16 |
| 2751 | SP_2751_SKILLF | skillF |
| 2752 | SP_2752_MOB_07DIMG_INFO_SKILL_03D_EFFECT | Mob/%07d.img/info/skill/%03d/effect |
| 2753 | SP_2753_MOB_07DIMG_INFO_SKILL_03D_HIT | Mob/%07d.img/info/skill/%03d/hit |
| 2754 | SP_2754_MOB_07DIMG_INFO_SKILL_03D_MOB | Mob/%07d.img/info/skill/%03d/mob |
| 2755 | SP_2755_SELECTED | selected |
| 2756 | SP_2756__SELECTED_0 | /selected/0 |
| 2757 | SP_2757__NORMAL_1 | /normal/1 |
| 2758 | SP_2758__PRESSED_1 | /pressed/1 |
| 2759 | SP_2759__DISABLED_1 | /disabled/1 |
| 2760 | SP_2760__MOUSEOVER_1 | /mouseOver/1 |
| 2761 | SP_2761__SELECTED_1 | /selected/1 |
| 2762 | SP_2762__NORMAL_2 | /normal/2 |
| 2763 | SP_2763__PRESSED_2 | /pressed/2 |
| 2764 | SP_2764__DISABLED_2 | /disabled/2 |
| 2765 | SP_2765__MOUSEOVER_2 | /mouseOver/2 |
| 2766 | SP_2766__SELECTED_2 | /selected/2 |
| 2767 | SP_2767_UI_UIWINDOWIMG_MINIGAME_COMMON_LOCK | UI/UIWindow.img/Minigame/Common/Lock |
| 2768 | SP_2768_UI_UIWINDOWIMG_MINIGAME_COMMON_UNLOCK | UI/UIWindow.img/Minigame/Common/Unlock |
| 2769 | SP_2769_THERE_IS_NO_ONE_TO_WHISPER_TO | There is no one to whisper to. |
| 2770 | SP_2770_ | ◀ |
| 2771 | SP_2771__ |  \| |
| 2772 | SP_2772__ |  ▶ |
| 2773 | SP_2773_YOUVE_GOT__R_N__S__R_N_D_PRESENTS_FROM_S | You've got \r\n [%s]\r\n %d presents from '%s' |
| 2774 | SP_2774_03D | %03d |
| 2775 | SP_2775_SD | s%d |
| 2776 | SP_2776_COCONUT | coconut |
| 2777 | SP_2777_COUNTFALLING | countFalling |
| 2778 | SP_2778_COUNTBOMBING | countBombing |
| 2779 | SP_2779_COUNTSTOPPED | countStopped |
| 2780 | SP_2780_MAP_OBJ_ETCIMG_COCONUT_BACKGRND | Map/Obj/etc.img/coconut/backgrnd |
| 2781 | SP_2781_MAP_OBJ_ETCIMG_COCONUT_FONTSCORE | Map/Obj/etc.img/coconut/fontScore |
| 2782 | SP_2782_MAP_OBJ_ETCIMG_COCONUT_FONTTIME | Map/Obj/etc.img/coconut/fontTime |
| 2783 | SP_2783_MAP_OBJ_ETCIMG_CLOCK_FONTTIME | Map/Obj/etc.img/clock/fontTime |
| 2784 | SP_2784_02D02D | %02d:%02d |
| 2785 | SP_2785_SN | SN |
| 2786 | SP_2786_ITEMID | ItemId |
| 2787 | SP_2787_COUNT | Count |
| 2788 | SP_2788_PRICE | Price |
| 2789 | SP_2789_PRIORITY | Priority |
| 2790 | SP_2790_PERIOD | Period |
| 2791 | SP_2791_PREMIUM | Premium |
| 2792 | SP_2792_PREMIUM | premium |
| 2793 | SP_2793_REQLEV | reqLev |
| 2794 | SP_2794_GENDER | Gender |
| 2795 | SP_2795_ONSALE | OnSale |
| 2796 | SP_2796_SKILL_MOBSKILLIMG | Skill/MobSkill.img |
| 2797 | SP_2797_SKILL_MOBSKILLIMG_03D_LEVEL_D_EFFECT | Skill/MobSkill.img/%03d/level/%d/effect |
| 2798 | SP_2798_SKILL_MOBSKILLIMG_03D_LEVEL_D_HIT | Skill/MobSkill.img/%03d/level/%d/hit |
| 2799 | SP_2799_SKILL_MOBSKILLIMG_03D_LEVEL_D_MOB | Skill/MobSkill.img/%03d/level/%d/mob |
| 2800 | SP_2800_SUMMONEFFECT | summonEffect |
| 2801 | SP_2801_LIMIT | limit |
| 2802 | SP_2802_ROTATEPERIOD | rotatePeriod |
| 2803 | SP_2803_UI_BASICIMG_COMBOBOX | UI/Basic.img/ComboBox |
| 2804 | SP_2804_UI_BASICIMG_CURSOR_ARROW | UI/Basic.img/Cursor/arrow |
| 2805 | SP_2805_SOTREMBLE | soTremble |
| 2806 | SP_2806_NAME_OF_THE_CHARACTER_RECEIVING | Name of the character receiving: |
| 2807 | SP_2807_UI_UIWINDOWIMG_COUPON_TEXT_DISABLED | UI/UIWindow.img/Coupon/text/disabled |
| 2808 | SP_2808_UI_UIWINDOWIMG_COUPON_TEXT_NORMAL | UI/UIWindow.img/Coupon/text/normal |
| 2809 | SP_2809_UI_UIWINDOWIMG_MINIGAME_COMMON_TEXT_DISABLED | UI/UIWindow.img/Minigame/Common/text/disabled |
| 2810 | SP_2810_UI_UIWINDOWIMG_MINIGAME_COMMON_TEXT_NORMAL | UI/UIWindow.img/Minigame/Common/text/normal |
| 2811 | SP_2811_PASSWORD | Password |
| 2812 | SP_2812__S__R_NHAS_PASSED_ITS_EXPIRATION_DATE_R_NAND_WILL_BE_REMOVED_R_NFROM_YOUR_INVENT | [%s]\r\nhas passed its expiration date\r\nand will be removed\r\nfrom your inventory. |
| 2813 | SP_2813_EFFECT_BASICEFFIMG_ASSAULTER_REMAIN | Effect/BasicEff.img/Assaulter/remain |
| 2814 | SP_2814_EFFECT_BASICEFFIMG_ASSAULTER_EFFECT | Effect/BasicEff.img/Assaulter/effect |
| 2815 | SP_2815__SLIDEALL | /slideall |
| 2816 | SP_2816__SLIDECH | /slidech |
| 2817 | SP_2817__SLIDEMAP | /slidemap |
| 2818 | SP_2818_SCROLLDISABLE | scrollDisable |
| 2819 | SP_2819_BULLETSPEED | bulletSpeed |
| 2820 | SP_2820_SUMMONED | Summoned |
| 2821 | SP_2821_SPECIALATTACK | specialAttack |
| 2822 | SP_2822__SLIDEALLX | /slideallx |
| 2823 | SP_2823__SLIDECHX | /slidechx |
| 2824 | SP_2824__SLIDEMAPX | /slidemapx |
| 2825 | SP_2825__SLIDEAX | /slideax |
| 2826 | SP_2826__LOG | /log |
| 2827 | SP_2827_DELAYEDHIT | DelayedHit |
| 2828 | SP_2828_UI_UIWINDOWIMG_NOTICE_0 | UI/UIWindow.img/Notice/0 |
| 2829 | SP_2829_UI_UIWINDOWIMG_NOTICE_1 | UI/UIWindow.img/Notice/1 |
| 2830 | SP_2830_UI_UIWINDOWIMG_NOTICE_2 | UI/UIWindow.img/Notice/2 |
| 2831 | SP_2831_UI_UIWINDOWIMG_NOTICE_3 | UI/UIWindow.img/Notice/3 |
| 2832 | SP_2832_UI_UIWINDOWIMG_NOTICE_4 | UI/UIWindow.img/Notice/4 |
| 2833 | SP_2833_UI_UIWINDOWIMG_NOTICE_BTOK | UI/UIWindow.img/Notice/btOK |
| 2834 | SP_2834_UI_BASICIMG_BTYES2 | UI/Basic.img/BtYes2 |
| 2835 | SP_2835_UI_BASICIMG_BTNO2 | UI/Basic.img/BtNo2 |
| 2836 | SP_2836_ETC_BLOCKREASONIMG | Etc/BlockReason.img |
| 2837 | SP_2837_YOU_CAN_LOGIN_AFTER_R_NS_2D_4D_2D02D_S | You can login after\r\n%s/%2d/%4d %2d:%02d %s |
| 2838 | SP_2838_YOU_DONT_HAVE_ENOUGH_HP_TO_USE_THIS_SKILL | You don't have enough HP to use this skill. |
| 2839 | SP_2839_YOU_DONT_HAVE_ENOUGH_MP_TO_USE_THIS_SKILL | You don't have enough MP to use this skill. |
| 2840 | SP_2840_YOU_DONT_HAVE_ENOUGH_MESOS_TO_USE_THIS_SKILL | You don't have enough mesos to use this skill. |
| 2841 | SP_2841_YOU_DONT_HAVE_ENOUGH_S_TO_USE_THIS_SKILL | You don't have enough %s to use this skill. |
| 2842 | SP_2842_YOU_DO_NOT_HAVE_ENOUGH_THROWING_STARS_TO_USE_THIS_SKILL | You do not have enough throwing stars to use this skill. |
| 2843 | SP_2843_YOU_DO_NOT_HAVE_ENOUGH_BULLETS_TO_USE_THIS_SKILL | You do not have enough bullets to use this skill. |
| 2844 | SP_2844_YOU_CANNOT_USE_THIS_SKILL_WITH_THE_WEAPON_YOURE_EQUIPPED_WITH | You cannot use this skill with the weapon you're equipped with. |
| 2845 | SP_2845_YOU_CANNOT_USE_THIS_WEAPON_WITH_THE_CURRENT_STATS | You cannot use this weapon with the current stats. |
| 2846 | SP_2846_SINCE_YOU_ARE_NOT_USING_A_WEAPON_YOU_ARE_UNABLE_TO_ATTACK | Since you are not using a weapon, you are unable to attack. |
| 2847 | SP_2847_ONLY_USABLE_ON_AUTO_HP_POTION_POUCH | Only usable on Auto HP Potion Pouch. |
| 2848 | SP_2848_ONLY_USABLE_ON_AUTO_MP_POTION_POUCH | Only usable on Auto MP Potion Pouch. |
| 2849 | SP_2849_YOU_HAVE_USED_UP_ALL_OF_YOUR_ARROWS | You have used up all of your arrows. |
| 2850 | SP_2850_YOU_HAVE_USED_UP_ALL_OF_YOUR_THROWING_STARS | You have used up all of your throwing-stars. |
| 2851 | SP_2851_YOU_NEED_TO_EQUIP_A_PISTOL | You need to equip a pistol. |
| 2852 | SP_2852_UI_UIWINDOWIMG_TRADINGROOM_BTCANCEL | UI/UIWindow.img/TradingRoom/BtCancel |
| 2853 | SP_2853_UI_CASHSHOPIMG_CSICON_D | UI/CashShop.img/CSIcon/%d |
| 2854 | SP_2854_UI_UIWINDOWIMG_TRUNK_SELECT | UI/UIWindow.img/Trunk/select |
| 2855 | SP_2855_UI_UIWINDOW_TRUNK_BTEXIT | UI/UIWindow/Trunk/BtExit |
| 2856 | SP_2856_UI_UIWINDOW_TRUNK_BTOUTCOIN | UI/UIWindow/Trunk/BtOutCoin |
| 2857 | SP_2857_UI_UIWINDOW_TRUNK_BTINCOIN | UI/UIWindow/Trunk/BtInCoin |
| 2858 | SP_2858_UI_UIWINDOW_TRUNK_BTCOIN | UI/UIWindow/Trunk/BtCoin |
| 2859 | SP_2859_UI_UIWINDOWIMG_TRUNK_TAB_ENABLED | UI/UIWindow.img/Trunk/Tab/enabled |
| 2860 | SP_2860_UI_UIWINDOWIMG_TRUNK_TAB_DISABLED | UI/UIWindow.img/Trunk/Tab/disabled |
| 2861 | SP_2861_UI_UIWINDOW_STOREBANK_BTEXIT | UI/UIWindow/StoreBank/BtExit |
| 2862 | SP_2862_SOCHATTYPE | soChatType |
| 2863 | SP_2863_DISEASE | disease |
| 2864 | SP_2864_THE_OPTION_IS_NOT_AVAILABLE_FOR_THAT_PACKAGE | The option is not available for that package. |
| 2865 | SP_2865_ETC_CASHPACKAGEIMG | Etc/CashPackage.img |
| 2866 | SP_2866_RECT | rect |
| 2867 | SP_2867_MAP_OBJ_ETCIMG_TIMER_BACKGRND | Map/Obj/etc.img/timer/backgrnd |
| 2868 | SP_2868_UI_LOGINIMG_NOTICE_BTNEXON | UI/Login.img/Notice/BtNexon |
| 2869 | SP_2869_AREACOUNT | areaCount |
| 2870 | SP_2870_EFFECT_BASICEFFIMG_SUMMONED | Effect/BasicEff.img/Summoned |
| 2871 | SP_2871_HTTP__WWWNEXONNET | http://www.nexon.net |
| 2872 | SP_2872_AREAWARNING | areaWarning |
| 2873 | SP_2873_AREAATTACK | areaAttack |
| 2874 | SP_2874_THIS_PROCESS_CANNOT_BE_OPEN_WITH_MAPLESTORY_ONCE_YOU_END_THE_PROGRAM_THE_DATA_UN | This process cannot be open with MapleStory. Once you end the program, the data under it will not be saved. Are you sure you want to close the program? |
| 2875 | SP_2875_THE_ID_HAS_BEEN_PERMANENTLY_BLOCKED_R_NSO_YOU_WONT_BE_ABLE_TO_USE_THIS_ACCOUNT | The ID has been permanently blocked.\r\nSo you won't be able to use this account. |
| 2876 | SP_2876_ITEM_SPECIAL_0910IMG_D | Item/Special/0910.img/%d |
| 2877 | SP_2877_ITEM_SPECIAL_0911IMG_D | Item/Special/0911.img/%d |
| 2878 | SP_2878_ITEM_SPECIAL_0900IMG_08D | Item/Special/0900.img/%08d |
| 2879 | SP_2879_UI_CASHSHOPIMG_CSDISCOUNT | UI/CashShop.img/CSDiscount |
| 2880 | SP_2880_TOTAL | total |
| 2881 | SP_2881_SORT_IN_ORDER_OF_PRICE | Sort in order of price. |
| 2882 | SP_2882_SORT_IN_ORDER_OF_POPULARITY | Sort in order of popularity |
| 2883 | SP_2883_SORT_FROM_THE_NEWEST_ITEM | Sort from the newest item. |
| 2884 | SP_2884_GOQUICKTYPE | goQuickType |
| 2885 | SP_2885_LASTCONNECTEDVERSION | LastConnectedVersion |
| 2886 | SP_2886_THE_ITEM__S__HAS_BEEN_EXPIRED_AND_THEREFORE_DELETED_FROM_YOUR_INVENTORY | The item [%s] has been expired, and therefore, deleted from your inventory. |
| 2887 | SP_2887_DATEEXPIRE | dateExpire |
| 2888 | SP_2888_TIMELIMITED | timeLimited |
| 2889 | SP_2889_COCHATWNDH | coChatWndH |
| 2890 | SP_2890_UI_BASICIMG_CURSOR_D | UI/Basic.img/Cursor/%d |
| 2891 | SP_2891_YOU_CANNOT_TAKE_SCREENSHOTS_AT_THIS_MOMENT_PLEASE_TRY_AGAIN_LATER | You cannot take screenshots at this moment. Please try again later. |
| 2892 | SP_2892_UI_STATUSBARIMG_BASE_CHAT | UI/StatusBar.img/base/chat |
| 2893 | SP_2893_DEADLYATTACK | deadlyAttack |
| 2894 | SP_2894_TREMBLE | tremble |
| 2895 | SP_2895_VER_DD | Ver. %d.%d |
| 2896 | SP_2896_VER_TEST_DD | Ver. Test %d.%d |
| 2897 | SP_2897_SELFDESTRUCTION | selfDestruction |
| 2898 | SP_2898_INVINCIBLE | invincible |
| 2899 | SP_2899_EFFECTDISTANCE | effectDistance |
| 2900 | SP_2900_RANDOMPOS | randomPos |
| 2901 | SP_2901_DOFIRST | doFirst |
| 2902 | SP_2902_UI_CASHSHOPIMG_CSCHAR_BTTAKEOFFAVATAR | UI/CashShop.img/CSChar/BtTakeoffAvatar |
| 2903 | SP_2903_STRING_TOOLTIPHELPIMG_SHOP_BUTTON_TAKEOFFAVATAR | String/ToolTipHelp.img/Shop/Button/TakeoffAvatar |
| 2904 | SP_2904_UI_UIWINDOWIMG_ICONBASE_0 | UI/UIWindow.img/IconBase/0 |
| 2905 | SP_2905_MPBURN | mpBurn |
| 2906 | SP_2906_YOU_SKILL_HAS_BEEN_SEALED_UP_MAKING_YOU_UNABLE_TO_USE_IT | You skill has been sealed up, making you unable to use it. |
| 2907 | SP_2907_YOUVE_BEEN_WEAKENED_MAKING_YOU_UNABLE_TO_JUMP | You've been weakened, making you unable to jump. |
| 2908 | SP_2908_YOUVE_BEEN_STUNNED_MAKING_YOU_UNABLE_TO_MOVE | You've been stunned, making you unable to move. |
| 2909 | SP_2909_YOUVE_BEEN_CURSED_SO_THE_AMOUNT_OF_EXP_YOULL_RECEIVE_WILL_DECREASE | You've been cursed, so the amount of EXP you'll receive will decrease. |
| 2910 | SP_2910_YOU_ARE_IN_A_STATE_OF_DARKNESS_SO_YOUR_ACCURACY_WILL_BE_DECREASED | You are in a state of darkness, so your accuracy will be decreased. |
| 2911 | SP_2911_YOU_HAVE_BEEN_POISONED_SO_YOUR_HP_WILL_SLOWLY_DECREASE | You have been poisoned, so your HP will slowly decrease. |
| 2912 | SP_2912_UI_UIWINDOWIMG_UTILDLGEX_DOT0 | UI/UIWindow.img/UtilDlgEx/dot0 |
| 2913 | SP_2913_UI_UIWINDOWIMG_UTILDLGEX_DOT1 | UI/UIWindow.img/UtilDlgEx/dot1 |
| 2914 | SP_2914_SOHPFLASH | soHPFlash |
| 2915 | SP_2915_SOMPFLASH | soMPFlash |
| 2916 | SP_2916_UI_STATUSBARIMG_GAUGE_HPFLASH | UI/StatusBar.img/gauge/hpFlash |
| 2917 | SP_2917_UI_STATUSBARIMG_GAUGE_MPFLASH | UI/StatusBar.img/gauge/mpFlash |
| 2918 | SP_2918_UI_UIWINDOWIMG_MEGAPHONE_BACKGRND | UI/UIWindow.img/Megaphone/backgrnd |
| 2919 | SP_2919_UI_UIWINDOWIMG_ARTMEGAPHONE_BACKGRND | UI/UIWindow.img/ArtMegaphone/backgrnd |
| 2920 | SP_2920_UI_UIWINDOWIMG_ITEMMEGAPHONE_BACKGRND | UI/UIWindow.img/ItemMegaphone/backgrnd |
| 2921 | SP_2921_UI_UIWINDOWIMG_MEGAPHONE_CHD | UI/UIWindow.img/Megaphone/ch%d |
| 2922 | SP_2922_UI_UIWINDOWIMG_MEGAPHONE_NUM_D | UI/UIWindow.img/Megaphone/Num/%d |
| 2923 | SP_2923_IF_YOU_MOVE_BEFORE_YOU_OPEN_THE_STORE_THE_STORE_MAY_NOT_BE_OPENED | If you move before you open the store, the store may not be opened. |
| 2924 | SP_2924_THE_BUYER_DOES_NOT_POSSESS_ENOUGH_MONEY_TO_TRADE_ANYMORE | The buyer does not possess enough money to trade anymore. |
| 2925 | SP_2925_THE_PRICE_OF_THE_ITEM_IS_TOO_HIGH_FOR_THE_TRADE | The price of the item is too high for the trade. |
| 2926 | SP_2926_UI_UIWINDOWIMG_RESET_COMMON_BTUP | UI/UIWindow.img/Reset/Common/BtUP |
| 2927 | SP_2927_UI_UIWINDOWIMG_RESET_COMMON_BTDOWN | UI/UIWindow.img/Reset/Common/BtDown |
| 2928 | SP_2928_UI_UIWINDOWIMG_RESET_AP_BACKGRND | UI/UIWindow.img/Reset/AP/backgrnd |
| 2929 | SP_2929_UI_UIWINDOWIMG_RESET_AP_BACKGRND2 | UI/UIWindow.img/Reset/AP/backgrnd2 |
| 2930 | SP_2930_UI_UIWINDOWIMG_RESET_AP_BACKGRND3 | UI/UIWindow.img/Reset/AP/backgrnd3 |
| 2931 | SP_2931__D | (-%d) |
| 2932 | SP_2932_D | (+%d) |
| 2933 | SP_2933_UI_UIWINDOWIMG_RESET_AP_STATD_D | UI/UIWindow.img/Reset/AP/stat%d/%d |
| 2934 | SP_2934_UI_UIWINDOWIMG_RESET_SP_SKILL0 | UI/UIWindow.img/Reset/SP/skill0 |
| 2935 | SP_2935_UI_UIWINDOWIMG_RESET_SP_SKILL1 | UI/UIWindow.img/Reset/SP/skill1 |
| 2936 | SP_2936_UI_UIWINDOWIMG_RESET_SP_LINE | UI/UIWindow.img/Reset/SP/line |
| 2937 | SP_2937_UI_UIWINDOWIMG_RESET_SP_BACKGRND | UI/UIWindow.img/Reset/SP/backgrnd |
| 2938 | SP_2938_UI_UIWINDOWIMG_RESET_SP_BACKGRND1 | UI/UIWindow.img/Reset/SP/backgrnd1 |
| 2939 | SP_2939_UI_UIWINDOWIMG_RESET_SP_BACKGRND2 | UI/UIWindow.img/Reset/SP/backgrnd2 |
| 2940 | SP_2940_UI_UIWINDOWIMG_RESET_SP_BACKGRND3 | UI/UIWindow.img/Reset/SP/backgrnd3 |
| 2941 | SP_2941_UI_UIWINDOWIMG_RESET_SP_TAB_ENABLED | UI/UIWindow.img/Reset/SP/Tab/enabled |
| 2942 | SP_2942_UI_UIWINDOWIMG_RESET_SP_TAB_DISABLED | UI/UIWindow.img/Reset/SP/Tab/disabled |
| 2943 | SP_2943_UI_UIWINDOWIMG_RESET_SP_TAB_DISABLED_D | UI/UIWindow.img/Reset/SP/Tab/disabled/%d |
| 2944 | SP_2944_UI_UIWINDOWIMG_TELEPORT_BACKGRND | UI/UIWindow.img/Teleport/backgrnd |
| 2945 | SP_2945_UI_UIWINDOWIMG_TELEPORT_SELECT | UI/UIWindow.img/Teleport/select |
| 2946 | SP_2946_UI_UIWINDOWIMG_TELEPORT_BTREGISTER | UI/UIWindow.img/Teleport/BtRegister |
| 2947 | SP_2947_UI_UIWINDOWIMG_TELEPORT_BTDELETE | UI/UIWindow.img/Teleport/BtDelete |
| 2948 | SP_2948_UI_UIWINDOWIMG_TELEPORT_BTMOVE | UI/UIWindow.img/Teleport/BtMove |
| 2949 | SP_2949_S_IS_CURRENTLY_DIFFICULT_TO_LOCATE_SO_R_NTHE_TELEPORT_WILL_NOT_TAKE_PLACE | %s is currently difficult to locate, so\r\nthe teleport will not take place. |
| 2950 | SP_2950_YOU_CANNOT_TELEPORT_TO_THIS_MAP | You cannot teleport to this map. |
| 2951 | SP_2951_YOU_CANNOT_TARGET_YOURSELF_R_NFOR_THE_TELEPORT | You cannot target yourself\r\nfor the teleport. |
| 2952 | SP_2952_THIS_MAP_IS_NOT_AVAILABLE_TO_ENTER_FOR_THE_LIST | This map is not available to enter for the list. |
| 2953 | SP_2953_YOUR_TELEPORT_LIST_IS_FULL_R_NPLEASE_DELETE_AN_ENTRY_BEFORE_TRYING_AGAIN | Your teleport list is full.\r\nPlease delete an entry before trying again. |
| 2954 | SP_2954_YOU_HAVE_ALREADY_ENTERED_THIS_MAP | You have already entered this map. |
| 2955 | SP_2955_ITS_THE_MAP_YOURE_CURRENTLY_ON | It's the map you're currently on. |
| 2956 | SP_2956_USERS_BELOW_LEVEL_7_ARE_NOT_ALLOWED__R_NTO_GO_OUT_FROM_MAPLE_ISLAND | Users below level 7 are not allowed \r\nto go out from Maple Island. |
| 2957 | SP_2957_WILL_YOU_ENTER_THIS_MAP_R_NIN_YOUR_TELEPORT_LIST_R_N_S_ | Will you enter this map\r\nin your teleport list?\r\n[%s] |
| 2958 | SP_2958_WILL_YOU_DELETE_THIS_MAP_FROM_THE_R_NTELEPORT_LIST_R_N_S_ | Will you delete this map from the\r\nteleport list?\r\n[%s] |
| 2959 | SP_2959_WILL_YOU_TELEPORT_TO_THIS_MAP_R_N_S_ | Will you teleport to this map?\r\n[%s] |
| 2960 | SP_2960_STRING_TOOLTIPHELPIMG_GAME_BUTTON_MAPTRANSFER_REGISTER | String/ToolTipHelp.img/Game/Button/MapTransfer/Register |
| 2961 | SP_2961_STRING_TOOLTIPHELPIMG_GAME_BUTTON_MAPTRANSFER_REGISTEREX | String/ToolTipHelp.img/Game/Button/MapTransfer/RegisterEx |
| 2962 | SP_2962_STRING_TOOLTIPHELPIMG_GAME_BUTTON_MAPTRANSFER_DELETE | String/ToolTipHelp.img/Game/Button/MapTransfer/Delete |
| 2963 | SP_2963_STRING_TOOLTIPHELPIMG_GAME_BUTTON_MAPTRANSFER_MOVE | String/ToolTipHelp.img/Game/Button/MapTransfer/Move |
| 2964 | SP_2964_YOU_MAY_NOT_PURCHASE_THE_ITEM_R_NSINCE_YOU_ALREADY_HAVE_IT | You may not purchase the item\r\nsince you already have it. |
| 2965 | SP_2965_THIS_ITEM_CANNOT_BE_TRANSFERED_BECAUSE_R_NYOU_ALREADY_OWN_THIS_ITEM | This item cannot be transfered because\r\nyou already own this item. |
| 2966 | SP_2966_THE_EXP_DID_NOT_DROP_AFTER_USING_THE_SAFETY_CHARM_ONCED_DAYS_D_TIMES_LEFT | The EXP did not drop after using the Safety Charm once.(%d days/%d times left) |
| 2967 | SP_2967_THE_EXP_DID_NOT_DROP_AFTER_USING_S_ITEM | The EXP did not drop after using %s item. |
| 2968 | SP_2968_CHOCOSTICK | Chocostick |
| 2969 | SP_2969_EVERLAST | everlast |
| 2970 | SP_2970_REACTOR | Reactor |
| 2971 | SP_2971_DRAG | drag |
| 2972 | SP_2972_FORCE | force |
| 2973 | SP_2973_QUEST | quest |
| 2974 | SP_2974_PQUEST | pquest |
| 2975 | SP_2975_EFFECT_SETEFFIMG_D_BACKGRND | Effect/SetEff.img/%d/backgrnd |
| 2976 | SP_2976_RANKING_NOT_AVAILABLE | Ranking Not Available |
| 2977 | SP_2977_RANKED_AT_D | Ranked at %d |
| 2978 | SP_2978_UI_LOGINIMG_CHARSELECT_SCROLL_1D | UI/Login.img/CharSelect/scroll/%1d |
| 2979 | SP_2979_UI_LOGINIMG_CHARSELECT_ICON_S | UI/Login.img/CharSelect/icon/%s |
| 2980 | SP_2980_REACTOR | reactor |
| 2981 | SP_2981_EFFECTTYPE | effectType |
| 2982 | SP_2982_NOFLIP | noFlip |
| 2983 | SP_2983_THIS_ITEM_IS_UNAVAILABLE_FOR_THE_PICK_UP | This item is unavailable for the pick-up. |
| 2984 | SP_2984_YOU_CANNOT_GO_TO_THAT_PLACE | You cannot go to that place. |
| 2985 | SP_2985_YOU_CANNOT_TELEPORT_TO_OR_ON_THIS_MAP | You cannot teleport to or on this map. |
| 2986 | SP_2986_UNABLE_TO_APPROACH_DUE_TO_THE_FORCE_OF_THE_GROUND_NOW_MOVING_TO_THE_NEAREST_TOWN | Unable to approach due to the force of the ground. Now moving to the nearest town. |
| 2987 | SP_2987_MAP_MAP_AREACODEIMG | Map/Map/AreaCode.img |
| 2988 | SP_2988_WOSHIGANMADE | woshiganmade |
| 2989 | SP_2989_UI_UIWINDOWIMG_BETAEDITION | UI/UIWindow.img/BetaEdition |
| 2990 | SP_2990_BETAEDITION | BetaEdition |
| 2991 | SP_2991_UI_LOGINIMG_COMMON_LOGINLOCATION | UI/Login.img/Common/loginlocation |
| 2992 | SP_2992_UI_LOGINIMG_BTOK | UI/Login.img/BtOk |
| 2993 | SP_2993_UI_GATEWAYIMG_WORLDSELECT_BTDOT | UI/Gateway.img/WorldSelect/BtDot |
| 2994 | SP_2994_UI_GATEWAYIMG_WORLDSELECT_BTQUIT | UI/Gateway.img/WorldSelect/BtQuit |
| 2995 | SP_2995_GAMEMASTER | gamemaster |
| 2996 | SP_2996_UNABLE_TO_SEND_THE_MESSAGE_PLEASE_ENTER_THE_USERS_NAME_BEFORE_WARNING | Unable to send the message. Please enter the user's name before warning. |
| 2997 | SP_2997_YOUR_WARNING_HAS_BEEN_SUCCESSFULLY_SENT | Your warning has been successfully sent. |
| 2998 | SP_2998_HACKING | Hacking |
| 2999 | SP_2999_BOTTING | Botting |
| 3000 | SP_3000_SCAMMING | Scamming |
| 3001 | SP_3001_FAKE_GM | Fake GM |
| 3002 | SP_3002_HARASSMENT | Harassment |
| 3003 | SP_3003_YOU_HAVE_SUCCESSFULLY_REPORTED_THE_USER | You have successfully reported the user. |
| 3004 | SP_3004_UNABLE_TO_LOCATE_THE_USER | Unable to locate the user. |
| 3005 | SP_3005_YOU_MAY_ONLY_REPORT_USERS_10_TIMES_A_DAY | You may only report users 10 times a day. |
| 3006 | SP_3006_YOU_HAVE_BEEN_REPORTED_TO_THE_GMS_BY_A_USER | You have been reported to the GM's by a user. |
| 3007 | SP_3007_YOUR_REQUEST_DID_NOT_GO_THROUGH_FOR_UNKNOWN_REASONS_PLEASE_TRY_AGAIN_LATER | Your request did not go through for unknown reasons. Please try again later. |
| 3008 | SP_3008_UI_UIWINDOWIMG_CONTEXTMENU_BTALERTGM | UI/UIWindow.img/ContextMenu/BtAlertGM |
| 3009 | SP_3009_UI_UIWINDOWIMG_CONTEXTMENU_BTBLOCK | UI/UIWindow.img/ContextMenu/BtBlock |
| 3010 | SP_3010_UI_UIWINDOWIMG_REPORT_BACKGRND | UI/UIWindow.img/Report/backgrnd |
| 3011 | SP_3011_UI_UIWINDOWIMG_ADMIN_BLOCK_BACKGRND | UI/UIWindow.img/Admin/Block/backgrnd |
| 3012 | SP_3012_THE_CASH_SHOP_IS_CURRENTLY_R_NNOT_AVAILABLE_R_NSTAY_TUNED | The cash shop is currently\r\nnot available.\r\nStay tuned... |
| 3013 | SP_3013_UI_UIWINDOWIMG_SOFTKEYBOARD_BACKGRND_1_BACK | UI/UIWindow.img/SoftKeyboard/backgrnd/1/back |
| 3014 | SP_3014_UI_UIWINDOWIMG_SOFTKEYBOARD_BACKGRND_1_KEYBOARD | UI/UIWindow.img/SoftKeyboard/backgrnd/1/keyboard |
| 3015 | SP_3015_UI_UIWINDOWIMG_SOFTKEYBOARD_BT_1_BTCLOSE | UI/UIWindow.img/SoftKeyboard/Bt/1/BtClose |
| 3016 | SP_3016_UI_UIWINDOWIMG_SOFTKEYBOARD_FUNCKEY_BACKSPACE | UI/UIWindow.img/SoftKeyboard/funckey/backspace |
| 3017 | SP_3017_UI_UIWINDOWIMG_SOFTKEYBOARD_FUNCKEY_ENTER | UI/UIWindow.img/SoftKeyboard/funckey/enter |
| 3018 | SP_3018_UI_UIWINDOWIMG_SOFTKEYBOARD_KEY | UI/UIWindow.img/SoftKeyboard/key |
| 3019 | SP_3019_D_NORMAL_0 | %d/normal/0 |
| 3020 | SP_3020_D_PRESSED_0 | %d/pressed/0 |
| 3021 | SP_3021_D_DISABLED_0 | %d/disabled/0 |
| 3022 | SP_3022_D_MOUSEOVER_0 | %d/mouseOver/0 |
| 3023 | SP_3023_EFFECT_BASICEFFIMG_GM | Effect/BasicEff.img/GM |
| 3024 | SP_3024_EULAVER | EULAVer. |
| 3025 | SP_3025_CLINETVER | ClinetVer. |
| 3026 | SP_3026_EULAAGREEMENT | EULAAgreement |
| 3027 | SP_3027_UI_LOGINIMG_BTCANCEL | UI/Login.img/BtCancel |
| 3028 | SP_3028_UI_LOGINIMG_TOS_0 | UI/Login.img/TOS/0 |
| 3029 | SP_3029_EEND_USER_LICENSE_AGREEMENTN_R_N_R_N_R_NTHIS_END_USER_LICENSE_AGREEMENT_THIS_EAG | #eEND USER LICENSE AGREEMENT#n\r\n\r\n\r\nTHIS END USER LICENSE AGREEMENT (THIS "#eAGREEMENT#n") IS ENTERED INTO BETWEEN WIZET CORPORATION ("#eWIZET#n") AND YOU ("#eYOU#n" OR "#eYOUR#n").  IT IS IMPORTANT THAT YOU READ AND CAREFULLY UNDERSTAND THIS AGREEMENT.  BY CLICKING ON THE "OK" BUTTON LOCATED AT THE END OF THIS PAGE OR OTHERWISE INSTALLING THE SOFTWARE, YOU AGREE TO BE BOUND BY THE TERMS AND CONDITIONS OF THIS AGREEMENT.  IF YOU DO NOT AGREE WITH ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT AND DO NOT WISH TO BE BOUND, PLEASE CLICK THE "CANCEL" BUTTON.  IF YOU DO NOT ACCEPT THIS AGREEMENT, YOU WILL NOT BE ABLE TO ACCESS, DOWNLOAD OR USE THE SOFTWARE AND/OR THE SERVICE.  WIZET RESERVES THE RIGHT IN ITS SOLE DISCRETION AT ANY TIME TO CHANGE ANY OF THE TERMS AND CONDITIONS CONTAINED IN THIS AGREEMENT.  ANY CHANGES TO THIS AGREEMENT WILL BE EFFECTIVE UPON POSTING AT HTTP://MAPLESTORY.NEXON.NET.\r\n\r\n\r\n#e1.\tDEFINITIONS#n\r\n\r\nFor purposes of this Agreement, the following terms shall have the following meanings:\r\n1.1\t"#eDocumentation#n" means the online, electronic or printed user guides and other documentation which Wizet makes generally available which describes the installation and use of the Software.\r\n1.2\t"#eDerivative Work#n" means a new or modified work that is based on or derived from a preexisting work, including, without limitation, a work that, in the absence of a license, would infringe the copyright in such preexisting work or that uses trade secrets or other proprietary information with respect to such preexisting work.\r\n1.3\t"#eGame#n" means Wizet's "Massively Multiplayer Online Role-Playing Game" currently entitled "MapleStory."\r\n |
| 3030 | SP_3030_14EINTELLECTUAL_PROPERTY_RIGHTSN_MEANS_WORLDWIDE_STATUTORY_AND_COMMON_LAW_RIGHTS | 1.4\t"#eIntellectual Property Rights#n" means worldwide statutory and common law rights associated with (a) patents and patent applications; (b) works of authorship, including copyrights, copyright applications, copyright registrations and "moral rights"; (c) the protection of trade and industrial secrets and confidential information; (d) Trademarks (as defined below); and (e) divisions, continuations, renewals and re-issuances of any of the foregoing, now existing or acquired in the future.\r\n1.5\t"#eService#n" shall mean the service by which the Software and related services hosted on servers controlled by Wizet and, as applicable, its designees, are made available through the Internet for remote use by third parties.\r\n1.6\t"#eSoftware#n" means the Game software, in object code form only, and the media, Documentation and Updates for which you are granted a license pursuant to this Agreement.\r\n1.7\t"#eTerm#n" shall have the meaning set forth in Section 8.1.\r\n1.8\t"#eTerms of Use#n" means Wizet's terms of use for the Service located at http://maplestory.nexon.net.\r\n1.9\t"#eTrademarks#n" shall mean (a) the trademarks, trade names, and service marks used by a party, whether registered or unregistered; (b) the respective stylistic marks and distinctive logotypes for such trademarks, trade names, and service marks; and (c) such other marks and logotypes as either party may designate from time to time in writing.\r\n1.10\t"#eUpdates#n" means the object code forms of any modifications, error corrections, bug fixes, new releases, or other updates of or to the Software that may be provided or otherwise made available hereunder by Wizet to you during the Term.\r\n |
| 3031 | SP_3031__R_N_R_NE2GRANT_OF_LICENSE_RESTRICTIONSN_R_N_R_N21ELIMITED_GRANT_OF_LICENSE_TERM | \r\n\r\n#e2.\tGRANT OF LICENSE; RESTRICTIONS#n\r\n\r\n2.1\t#eLimited Grant of License/Terms of Use#n.  Subject to the terms and conditions of this Agreement, Wizet hereby grants you a non-exclusive, nontransferable, nonsublicensable, limited right and license, during the Term, to access and use the Software, solely in accordance with the Documentation and solely for your own personal use.  Except as set forth in this Section 2.1, no other right or license of any kind is granted by Wizet to you hereunder with respect to the Software.  In order to use the Software and the Service, you must register for an account and otherwise agree to terms and conditions of the Terms of Use, which are incorporated herein by this reference.\r\n |
| 3032 | SP_3032_22ERESTRICTIONSN__YOU_HEREBY_ACKNOWLEDGE_AND_AGREE_THAT_YOU_SHALL_NOT_USE_THE_SO | 2.2\t#eRestrictions#n.  You hereby acknowledge and agree that you shall not use the Software for any purpose other than entertainment purposes, and that you shall use the Software in accordance with all applicable laws, rules, and regulations.  Except as expressly provided herein, you shall not, and shall not permit any third party to: (a) copy all or any portion of the Software; (b) decompile, disassemble or otherwise reverse engineer the Software, or any portion thereof, or determine or attempt to determine any source code, algorithms, methods or techniques used or embodied in the Software or any portion thereof; (c) modify, translate, or create any Derivative Works based upon the Software; (d) distribute, disclose, market, rent, lease, assign, sublicense, pledge or otherwise transfer the Software, in whole or in part, to any third party; (e) host, provide or develop matchmaking services for the Software or intercept, emulate or redirect the communication protocols used by Wizet, or its designees, in any way, including, without limitation, through protocol emulation, tunneling, packet sniffing, modifying or adding components to the Software, use of a utility program or any other technique now known or hereafter developed, for any purpose, including, but not limited to, unauthorized network play over the Internet, network play utilizing commercial or non-commercial gaming networks or as part of content aggregation networks; (f) create, use or maintain any unauthorized connections to the Software; (g) remove or alter any copyright, Trademark, or other proprietary notices, legends, symbols, or labels appearing on or in the Software; (h) perform, or release the results of, benchmark tests or other comparisons of the Software with other software or materials; (i) use the Software for any purpose other than in accordance with the terms and conditions of this Agreement.\r\n |
| 3033 | SP_3033_23EGRANT_OF_LICENSE_IN_YOUR_MATERIALSN__YOU_HEREBY_GRANT_WIZET_A_WORLDWIDE_ROYAL | 2.3\t#eGrant of License in Your Materials#n.  You hereby grant Wizet a worldwide, royalty-free, non-exclusive, limited, perpetual, nontransferable right and license to: (a) use, reproduce, distribute, transmit, perform (publicly, digitally, or otherwise), display (publicly or otherwise) and make derivative works of any and all data, information, content and other materials prepared by you through the use of, stored by you in connection with, or transmitted by you by or through, the Software ("#eYour Materials#n") for purposes of making the Software available to you and for internal analytic, statistical, security, quality control, and similar purposes; and (b) sublicense the foregoing rights to Wizet's designees.  You represent and warrant to Wizet that you have the right to grant the foregoing licenses in Your Materials.\r\n\r\n\r\n#e3.\tSERVICE#n\r\n\r\n3.1\t#eHosting#n.  During the Term, Wizet and/or its designees shall host and maintain the Service, and provide access to Service through the use of the Software, subject to the terms and conditions of this Agreement and the Terms of Use.  You acknowledge and agree that the Software is used to play the Game through the Service and that Wizet may, in its sole discretion, terminate or otherwise discontinue providing the Service pursuant to the Terms of Use.\r\n |
| 3034 | SP_3034_32EUPDATESN__DURING_THE_TERM_WIZET_MAY_PROVIDE_YOU_WITH_UPDATES_AS_THEY_ARE_MADE | 3.2\t#eUpdates#n.  During the Term, Wizet may provide you with Updates as they are made generally available by Wizet.  You acknowledge that, to the extent Wizet licenses some or all of the Software and/or other components of the Service from third party vendors ("#eVendors#n"), such Vendors, and not Wizet, are responsible for creating Updates, if any, and making them available for installation or distribution.  You acknowledge and agree that Wizet may, without your knowledge and/or consent, provide Updates to you remotely, including without limitation, by accessing the computer in which you store the Software.  You hereby grant Wizet your express consent to provide Updates to you by any and all means, with or without your knowledge and/or consent.  Any Update provided or made available by Wizet hereunder shall be deemed part of the Software and shall be subject to the terms and conditions of this Agreement.\r\n3.3\t#eYour Further Obligations#n.  You shall be solely responsible for any and all costs and fees in connection with accessing and using the Software and/or the Service, including, without limitation, Internet service provider fees, telecommunications fee and the costs of any and all equipment used by you in connection with the Software and/or the Service.  You acknowledge that Wizet shall have no obligation to assist you in using or accessing the Software, except as expressly set forth in this Agreement.  In addition, you agree to adhere at all times to the Terms of Use made available through the Service, as may be amended from time to time.\r\n\r\n\r\n#e4.\tPROPRIETARY RIGHTS#n\r\n\r\n |
| 3035 | SP_3035_AS_BETWEEN_WIZET_AND_YOU_WIZET_OWNS_AND_SHALL_RETAIN_ALL_RIGHT_TITLE_AND_INTERES | As between Wizet and you, Wizet owns and shall retain all right, title and interest, including, without limitation, all Intellectual Property Rights, in and to the Software, and any portions thereof, including, without limitation, any copy or Derivative Work of the Software (or any portion thereof) and any Updates thereto.  You agree to take any action reasonably requested by Wizet to evidence, maintain, enforce or defend the foregoing rights.  You shall not take any action to jeopardize, encumber, limit or interfere in any manner with Wizet's ownership of and rights with respect to the Software, or any Derivative Work or Update thereof or thereto.  You shall have only those rights in and to the Software and any Derivative Work or Update thereto as are expressly granted to you under this Agreement or the Terms of Use.\r\n\r\n\r\n#e5.\tCONFIDENTIAL INFORMATION#n\r\n\r\n5.1\t#eConfidential Information#n.  You acknowledge that, in the course of using the Software and exercising your rights under this Agreement, you may obtain confidential information relating to the Software, the Service or Wizet and its Vendors or other parties ("#eConfidential Information#n").  Such Confidential Information shall, as between you and Wizet, belong solely to Wizet and shall include, without limitation, the Software (including any and all Derivative Works and Updates),  the existence of and terms of this Agreement, trade secrets, know-how, inventions (whether or not patentable), techniques, processes, programs, ideas, algorithms, formulas, schematics, testing procedures, software design and architecture, computer code, internal documentation, design and functional specifications, product requirements, problem reports, performance information, documents, and other technical, business, product, marketing, third party customers, and financial information, plans and data.\r\n |
| 3036 | SP_3036_52EUSE_AND_DISCLOSURE_RESTRICTIONSN__YOU_HEREBY_ACKNOWLEDGE_AND_AGREE_THAT_THE_C | 5.2\t#eUse and Disclosure Restrictions#n.  You hereby acknowledge and agree that the Confidential Information constitutes and contains valuable proprietary information and trade secrets of Wizet, and embodies substantial creative efforts and confidential information, ideas, and expressions.  Accordingly, you agree: (a) to protect the Confidential Information from unauthorized dissemination and use; (b) to use the Confidential Information only for the performance of your obligations and in connection with the exercise of your rights hereunder; (c) not to disclose or otherwise provide to any third party, without the prior written consent of Wizet, any Confidential Information or any part or parts thereof; (d) to undertake whatever action is necessary to prevent or remedy (or authorize Wizet to do so in your name) any breach of your confidentiality obligations set forth herein; (e) not to remove or destroy any proprietary or confidential legends or markings placed upon or contained within the Software, or on any other Confidential Information provided to you by Wizet; and (f) not to develop any other materials, products, or services containing any of the concepts or ideas contained in the Software or Service or any other Confidential Information.\r\n |
| 3037 | SP_3037_53EEXCLUSIONSN__THE_FOREGOING_RESTRICTIONS_PERTAINING_TO_THE_CONFIDENTIAL_INFORM | 5.3\t#eExclusions#n.  The foregoing restrictions pertaining to the Confidential Information shall not apply with respect to any Confidential Information that: (a) was or becomes publicly known through no fault of you; (b) was known by you before receipt from Wizet, as evidenced by your contemporaneous written records, (c) becomes known to you without confidential or proprietary restriction from a source other than Wizet that does not owe a duty of confidentiality to Wizet with respect to such Confidential Information; or (d) is independently developed by you without the use of the Confidential Information.  In addition, you may use or disclose Confidential Information to the extent (i) expressly approved by Wizet in writing, and (ii) you are legally compelled to disclose such Confidential Information; provided, however, prior to any such compelled disclosure you shall cooperate fully with Wizet in protecting against any such disclosure and/or obtaining a protective order narrowing the scope of such disclosure and/or use of the Confidential Information.\r\n\r\n\r\n#e6.\tNO WARRANTY/LIMITATION OF LIABILITY#n\r\n\r\n |
| 3038 | SP_3038_61EDISCLAIMER_OF_WARRANTIESNTHE_SOFTWARE_IS_PROVIDED_AS_IS_AND_WITHOUT_WARRANTIE | 6.1\t#eDisclaimer of Warranties#n.\tTHE SOFTWARE IS PROVIDED "AS IS" AND WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED.  TO THE FULLEST EXTENT PERMISSIBLE PURSUANT TO APPLICABLE LAW, WIZET AND ITS AFFILIATES, LICENSORS, SUPPLIERS, ADVERTISERS, PARTNERS, SPONSORS AND AGENTS DISCLAIM ALL WARRANTIES, EXPRESS OR IMPLIED, INCLUDING, WITHOUT LIMITATION, IMPLIED WARRANTIES OF TITLE, NON-INFRINGEMENT, ACCURACY, MERCHANTABILITY, AND FITNESS FOR A PARTICULAR PURPOSE, AND ANY WARRANTIES THAT MAY ARISE FROM COURSE OF DEALING, COURSE OF PERFORMANCE OR USAGE OF TRADE.  WIZET AND ITS AFFILIATES, LICENSORS, SUPPLIERS, ADVERTISERS, SPONSORS, PARTNERS AND AGENTS DO NOT WARRANT THAT YOUR USE OF THE SOFTWARE WILL BE UNINTERRUPTED, ERROR-FREE OR SECURE, THAT DEFECTS WILL BE CORRECTED, OR THAT THE SOFTWARE, AND/OR THE SERVER(S), SYSTEM(S) AND NETWORK(S) ON WHICH THE SOFTWARE IS HOSTED AND/OR OPERATES, ARE OR WILL BE FREE OF VIRUSES OR OTHER HARMFUL COMPONENTS.  YOU ACKNOWLEDGE THAT YOU ARE RESPONSIBLE FOR OBTAINING AND MAINTAINING ALL TELEPHONE, COMPUTER HARDWARE AND OTHER EQUIPMENT NEEDED TO ACCESS AND USE THE SOFTWARE AND ALL CHARGES RELATED THERETO.  YOU ASSUME ALL RESPONSIBILITY AND RISK FOR YOUR USE OF THE SOFTWARE AND YOUR RELIANCE THEREON.  NO OPINION, ADVICE OR STATEMENT OF WIZET OR ITS AFFILIATES, LICENSORS, SUPPLIERS, ADVERTISERS, SPONSORS, PARTNERS, AGENTS OR THIRD PARTY USERS, WHETHER MADE IN OR ON THE SOFTWARE OR OTHERWISE, SHALL CREATE ANY WARRANTY.  YOUR USE OF THE SOFTWARE IS ENTIRELY AT YOUR OWN RISK.\r\n |
| 3039 | SP_3039_62ELIMITATION_OF_LIABILITYN__NEITHER_WIZET_NOR_ANY_OF_ITS_AFFILIATES_LICENSORS_S | 6.2\t#eLimitation of Liability#n.  NEITHER WIZET NOR ANY OF ITS AFFILIATES, LICENSORS, SUPPLIERS, ADVERTISERS, SPONSORS OR PARTNERS, NOR WIZET'S OR OTHER ENTITIES' DIRECTORS, OFFICERS, EMPLOYEES, CONSULTANTS, AGENTS OR OTHER REPRESENTATIVES, ARE RESPONSIBLE OR LIABLE FOR ANY INDIRECT, INCIDENTAL, CONSEQUENTIAL, SPECIAL, EXEMPLARY, PUNITIVE OR OTHER DAMAGES (INCLUDING, WITHOUT LIMITATION, DAMAGES FOR LOSS OF BUSINESS, LOSS OF DATA OR LOST PROFITS), UNDER ANY CONTRACT, NEGLIGENCE, STRICT LIABILITY OR OTHER THEORY ARISING OUT OF OR RELATING IN ANY WAY TO THE SOFTWARE, EVEN IF WIZET (OR SUCH OTHER ENTITIES OR PERSONS) HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.  SOME JURISDICTIONS DO NOT ALLOW THE EXCLUSION OR LIMITATION OF INCIDENTAL OR CONSEQUENTIAL DAMAGES, SO THE ABOVE LIMITATION OR EXCLUSION MAY NOT APPLY TO YOU.  YOUR SOLE REMEDY FOR DISSATISFACTION WITH THE SOFTWARE, OR ANY PART THEREOF, IS TO STOP USING THE SOFTWARE.  THE SOLE AND EXCLUSIVE MAXIMUM LIABILITY TO WIZET FOR ALL DAMAGES, LOSSES AND CAUSES OF ACTION, WHETHER IN CONTRACT, TORT (INCLUDING, WITHOUT LIMITATION, NEGLIGENCE) OR OTHERWISE, SHALL BE A MAXIMUM OF THE LESSER OF TWENTY  U.S. DOLLARS OR THE TOTAL CUMULATIVE AMOUNT PAID BY YOU TO PURCHASE CASH ITEMS AS SUCH IS DESCRIBED IN THE TERMS OF USE.\r\n6.3\t#eOther#n.  Without limiting the foregoing, Wizet shall have no obligations, responsibilities and/or liabilities if any nonconformance or failure of, or error in, the Software is caused by: (a) use of any attachment, feature, hardware, software or device in connection with the Software; (b) transportation, neglect or misuse of the Software or any use of the Software that is not in accordance with this Agreement and/or the Documentation; (c) alteration, modification, or enhancement of the Software; or (d) failure to provide a suitable installation or use environment for all or any part of the Software.\r\n |
| 3040 | SP_3040__R_N_R_NE7INDEMNIFICATIONN_R_N_R_NYOU_HEREBY_AGREE_TO_INDEMNIFY_DEFEND_AND_HOLD_ | \r\n\r\n#e7.\tINDEMNIFICATION#n\r\n\r\nYou hereby agree to indemnify, defend, and hold Wizet, our affiliates, licensors, suppliers, advertisers, sponsors and partners, and our and their directors, officers, employees, consultants, agents and other representatives, harmless from and against, any claim based upon: (a) the use, operation or combination of the Software with non-Wizet software, data, equipment or documentation if liability would have been avoided but for such use, operation, or combination; (b) use of other than the then-current, unaltered version of the Software; (c) your activities after Wizet has notified you that Wizet believes such activities may result in infringement; (d) any modifications to or markings of the Software; (e) any third party software; (f) any of Your Materials; or (g) your breach or alleged breach of this Agreement.\r\n\r\n\r\n#e8.\tTERM AND TERMINATION#n\r\n\r\n8.1\t#eTerm#n.  This Agreement shall commence on the date You click on the "OK" button below and shall continue until termination in accordance with this Section 8.\r\n8.2\t#eTermination#n.  This Agreement shall remain effective until terminated in accordance with its terms.  Wizet may terminate this Agreement in the event that you fail to comply with the terms and conditions contained herein or the terms and conditions contained in the Terms of Use.  Without limiting the foregoing, Wizet reserves the right to immediately terminate this Agreement, and/or your use of the Software or any portion thereof, at any time and for any reason, with or without cause.\r\n |
| 3041 | SP_3041_83EEFFECT_OF_TERMINATIONN__UPON_THE_EXPIRATION_OR_TERMINATION_OF_THIS_AGREEMENT_ | 8.3\t#eEffect of Termination#n.  Upon the expiration or termination of this Agreement, all rights and licenses granted to you hereunder shall immediately terminate.  Upon termination or expiration of this Agreement, you shall, at your sole expense, return to Wizet (or destroy, at Wizet's sole election) all Software and Confidential Information (and all copies and extracts thereof) then in your possession or under your control.   Termination of this Agreement shall not act as a waiver of any breach of this Agreement and shall not act as a release of your liability for breach of your obligations under this Agreement.  Wizet shall not be liable to you for damages of any kind solely as a result of terminating this Agreement in accordance with its terms.  Wizet's termination of this Agreement shall be without prejudice to any other right or remedy that it may have at law or in equity, and shall not relieve you of breaches occurring prior to the effective date of such termination.  The provisions of Sections 1 ("Definitions"), 2.2 ("Restrictions"), 4 ("Proprietary Rights"), 5 ("Confidential Information"), 6 ("No Warranty/Limitation of Liability"), 7 ("Indemnification"), 8.3 ("Effect of Termination") and 9 ("General Provisions"), shall survive the expiration or any termination of this Agreement.\r\n\r\n\r\n#e9.\tGENERAL PROVISIONS#n\r\n\r\n9.1\t#eAssignment#n.  This Agreement may not be assigned, in whole or part, whether voluntarily, by operation of law, or otherwise, by you without the prior written consent of Wizet.  Wizet may assign, license, delegate or otherwise transfer its rights or obligations hereunder to any third party without restriction.  Subject to the preceding sentences, the rights and liabilities of the parties hereto are binding on, and shall inure to the benefit of, the parties and their respective successors and permitted assigns.  Any attempted assignment other than in accordance with this Section 9.1 shall be null and void.\r\n |
| 3042 | SP_3042_92EGOVERNING_LAWN_JURISDICTION_AND_VENUE__THIS_AGREEMENT_SHALL_BE_GOVERNED_BY_AN | 9.2\t#eGoverning Law#n, Jurisdiction, and Venue.  This Agreement shall be governed by and construed in accordance with the laws of the State of California, U.S.A., without reference to its conflicts of law provisions.  Any dispute regarding this Agreement shall be subject to the exclusive jurisdiction of the California state courts in and for Los Angeles County, California, U.S.A. (or, if there is federal jurisdiction, the United States District Court for the Central District of California), and the parties hereby irrevocably agree to submit to the personal and exclusive jurisdiction and venue of such courts.  This Agreement shall not be governed by the United Nations Convention on Contracts for the International Sale of Goods, the application of which is hereby expressly excluded.\r\n9.3\t#eAttorneys' Fees#n.  If any legal action, including, without limitation, an action for arbitration or injunctive relief, is brought relating to this Agreement or the breach hereof, the prevailing party in any final judgment or arbitration award, or the non-dismissing party in the event of a dismissal without prejudice, shall be entitled to the full amount of all reasonable expenses, including all court costs, arbitration fees and actual attorney fees paid or incurred in good faith.\r\n9.4\t#eWaiver#n.  The waiver by either party of a breach of or a default under any provision of this Agreement, shall be in writing and shall not be construed as a waiver of any subsequent breach of or default under the same or any other provision of this Agreement, nor shall any delay or omission on the part of either party to exercise or avail itself of any right or remedy that it has or may have hereunder operate as a waiver of any right or remedy.\r\n |
| 3043 | SP_3043_95ESEVERABILITYN__IF_THE_APPLICATION_OF_ANY_PROVISION_OF_THIS_AGREEMENT_TO_ANY_P | 9.5\t#eSeverability#n.  If the application of any provision of this Agreement to any particular facts or circumstances shall be held to be invalid or unenforceable by an arbitration panel or a court of competent jurisdiction, then (a) the validity and enforceability of such provision as applied to any other particular facts or circumstances and the validity of other provisions of this Agreement shall not in any way be affected or impaired thereby, and (b) such provision shall be enforced to the maximum extent possible so as to effect the intent of the parties and reformed without further action by the parties to the extent necessary to make such provision valid and enforceable.\r\n9.6\t#eRelationship of the Parties#n.  Nothing contained in this Agreement shall be deemed or construed as creating a joint venture, partnership, agency, employment or fiduciary relationship between the parties.  Neither party, nor either party's agents, have any authority of any kind to bind the other party in any respect whatsoever, and the relationship of the parties is, and at all times shall continue to be, that of independent contractors.\r\n9.7\t#eForce Majeure#n.  Neither party shall be responsible or have any liability for any delay or failure to perform to the extent due to unforeseen circumstances or causes beyond its reasonable control, including, without limitation, acts of God, earthquake, fire, flood, embargoes, labor disputes and strikes, riots, war, acts of a public enemy, error in the coding of electronic files, Internet or other network "brownouts" or failures, power failures, novelty of product manufacture or other unanticipated product development problems, and acts of civil and military authorities.\r\n |
| 3044 | SP_3044_98EUS_GOVERNMENT_RIGHTSN__IF_YOU_ARE_OR_ARE_ENTERING_INTO_THIS_AGREEMENT_ON_BEHA | 9.8\t#eU.S. Government Rights#n.  If you are, or are entering into this Agreement on behalf of, any agency or instrumentality of the United States Government, the Software is "commercial computer software" and "commercial computer software documentation", and pursuant to FAR 12.212 or DFARS 227.7202, and their successors, as applicable, use, reproduction and disclosure of the Software is governed by the terms of this Agreement.\r\n9.9\t#eExport Controls#n.  You shall abide by all applicable export laws and regulations in its use of the Software.  The Software, or any part thereof, may not be downloaded or otherwise exported or re-exported (a) into any country for which the United States has a trade embargo, or (b) to anyone on the U.S. Treasury Department's list of Specially Designated Nationals or the U.S. Commerce Department's Table of Denial Orders.  You represent and warrant that you are not located in, under the control of, or a national or resident of any such country or on any such list.\r\n9.10\t#eCaptions and Section Headings#n.  The captions and Section and paragraph headings used in this Agreement are inserted for convenience only and shall not affect the meaning or interpretation of this Agreement.\r\n9.11\t#eEquitable Relief#n.  You acknowledge and agree that due to the unique nature of this Agreement, there can be no adequate remedy at law to compensate Wizet for your breach or threatened breach hereof; that any such breach or threatened breach will allow you or third parties to compete unfairly with Wizet resulting in irreparable harm to Wizet that would be difficult to measure; and, therefore, that upon any such breach or threat thereof, Wizet shall be entitled to injunctive and other appropriate equitable relief (without the necessity of proving actual damages or of posting a bond), in addition to whatever remedies it may have at law, hereunder, or otherwise.\r\n |
| 3045 | SP_3045_912EENTIRE_AGREEMENT_AMENDMENTN__THIS_AGREEMENT_TOGETHER_WITH_THE_TERMS_OF_USE_C | 9.12\t#eEntire Agreement; Amendment#n.  This Agreement (together with the Terms of Use) constitute the entire agreement between the parties concerning the subject matter hereof, and supersedes (a) all prior or contemporaneous representations, discussions, proposals, negotiations, conditions, agreements, and communications, whether oral or written, between the parties relating to the subject matter of this Agreement; and (b) all past courses of dealing and industry custom.\r\n\r\nBY CLICKING ON THE "OK" BUTTON BELOW, YOU ACKNOWLEDGE THAT (1) YOU HAVE READ AND REVIEWED THIS AGREEMENT IN ITS ENTIRETY, (2) YOU AGREE TO BE BOUND BY THIS AGREEMENT, (3) YOU HAVE THE POWER, AUTHORITY AND LEGAL RIGHT TO ENTER INTO THIS AGREEMENT AND (4) THIS AGREEMENT CONSTITUTES A BINDING AND ENFORCEABLE AGREEMENT.\r\n\r\n\r\n\r\n\r\n\r\n\r\n\r\n\r\n#eCopyright 2005 Wizet Corporation  --  All Rights Reserved\r\nUpdated : May 10, 2005#n |
| 3046 | SP_3046_UI_LOGIN_GENDER_TEXT_0 | UI/Login/Gender/text/0 |
| 3047 | SP_3047_UI_LOGIN_GENDER_TEXT_1 | UI/Login/Gender/text/1 |
| 3048 | SP_3048_UI_LOGIN_GENDER_BTYES | UI/Login/Gender/BtYes |
| 3049 | SP_3049_UI_LOGIN_GENDER_BTNO | UI/Login/Gender/BtNo |
| 3050 | SP_3050_UI_LOGINIMG_GENDER_SCROLL_0 | UI/Login.img/Gender/scroll/0 |
| 3051 | SP_3051_UI_LOGINIMG_GENDER_SCROLL_1 | UI/Login.img/Gender/scroll/1 |
| 3052 | SP_3052_STRING_EULAIMG_EULA | String/EULA.img/EULA |
| 3053 | SP_3053_UI_LOGINIMG_PINCODE_TEXT_D | UI/Login.img/Pincode/text/%d |
| 3054 | SP_3054_UI_LOGINIMG_PINCODE_BACKGRND_0 | UI/Login.img/Pincode/backgrnd/0 |
| 3055 | SP_3055_UI_LOGINIMG_PINCODE_BACKGRND_1 | UI/Login.img/Pincode/backgrnd/1 |
| 3056 | SP_3056_UI_LOGINIMG_PINCODE_BACKGRND_2 | UI/Login.img/Pincode/backgrnd/2 |
| 3057 | SP_3057_UI_LOGINIMG_PINCODE_BTYES | UI/Login.img/Pincode/BtYes |
| 3058 | SP_3058_UI_LOGINIMG_PINCODE_BTNO | UI/Login.img/Pincode/BtNo |
| 3059 | SP_3059_UI_LOGINIMG_PINCODE_BTCHGPIN | UI/Login.img/Pincode/BtChgPin |
| 3060 | SP_3060_UI_LOGINIMG_PINCODE_BTLOGIN | UI/Login.img/Pincode/BtLogin |
| 3061 | SP_3061_SD | %s%d |
| 3062 | SP_3062_COCONUT_SEASON_HAS_STARTED | Coconut Season has started! |
| 3063 | SP_3063_THE_GAME_OF_COCONUT_SEASON_HAS_ENDED_AND_YOULL_BE_TRANSPORTED_TO_A_DIFFERENT_MAP | The game of Coconut Season has ended, and you'll be transported to a different map. Please wait. |
| 3064 | SP_3064__USERCOUNT | /usercount |
| 3065 | SP_3065_TOTALD_MAPLED_STORYD | Total:%d Maple:%d Story:%d |
| 3066 | SP_3066_THE_GAME_ENDED_IN_A_TIE_SO_AN_ADDITIONAL_2_MINUTES_IS_REWARDED_FOR_OVERTIME | The game ended in a tie, so an additional 2 minutes is rewarded for overtime. |
| 3067 | SP_3067_IF_THE_GAME_ENDS_IN_A_TIE_AFTER_2_MINUTES_BOTH_TEAMS_WILL_BE_DEEMED_LOSERS_AND_T | If the game ends in a tie after 2 minutes, both teams will be deemed losers and the prizes will not be awarded. |
| 3068 | SP_3068_THE_GAME_BETWEEN_TEAM_MAPLE_AND_TEAM_STORY_HAVE_RESULTED_IN_A_TIE | The game between Team Maple and Team Story have resulted in a tie. |
| 3069 | SP_3069__TEAM_MAPLE_WINS |  Team Maple WINS! |
| 3070 | SP_3070__TEAM_STORY_WINS |  Team Story WINS! |
| 3071 | SP_3071__D_PEOPLE_HAVE_BEEN_ELIMINATED_FROM_THE_SPEED_OX_QUIZ |  %d people have been eliminated from the Speed OX Quiz. |
| 3072 | SP_3072_THE_GAME_OF_SNOWBALL_HAS_ENDED_AND_YOULL_BE_TRANSPORTED_TO_A_DIFFERENT_MAP_PLEAS | The game of SnowBall has ended, and you'll be transported to a different map. Please wait. |
| 3073 | SP_3073_SOUND_REACTORIMG_S_S | Sound/Reactor.img/%s/%s |
| 3074 | SP_3074_ANGERGAUGEANIMATION | AngerGaugeAnimation |
| 3075 | SP_3075_ANGERGAUGEEFFECT | AngerGaugeEffect |
| 3076 | SP_3076_REACTOR_SIMG | Reactor/%s.img |
| 3077 | SP_3077_EVENT | event |
| 3078 | SP_3078_REACTOR_07DIMG | Reactor/%07d.img |
| 3079 | SP_3079_D_HIT | %d/hit |
| 3080 | SP_3080_EVENT_D_HIT | event/%d/hit |
| 3081 | SP_3081_S | 's |
| 3082 | SP_3082_SPECIALLY_MADE_BY | Specially made by |
| 3083 | SP_3083_PICKUPALL | pickupAll |
| 3084 | SP_3084_PICKUPITEM | pickupItem |
| 3085 | SP_3085_PICKUPMESO | pickupMeso |
| 3086 | SP_3086_PICKUPOTHERS | pickupOthers |
| 3087 | SP_3087_CONSUMEHP | consumeHP |
| 3088 | SP_3088_CONSUMEMP | consumeMP |
| 3089 | SP_3089_SWEEPFORDROP | sweepForDrop |
| 3090 | SP_3090_DROPSWEEP | dropSweep |
| 3091 | SP_3091_LONGRANGE | longRange |
| 3092 | SP_3092_IGNOREPICKUP | ignorePickup |
| 3093 | SP_3093_WHITE | white |
| 3094 | SP_3094_BLACK | black |
| 3095 | SP_3095_HIDE | hide |
| 3096 | SP_3096_SHOW | show |
| 3097 | SP_3097_CLEAR | clear |
| 3098 | SP_3098_ADD | add |
| 3099 | SP_3099_BUFFITEMID | buffItemID |
| 3100 | SP_3100_UI_BASICIMG_BTOK3 | UI/Basic.img/BtOK3 |
| 3101 | SP_3101_UI_BASICIMG_BTCANCEL3 | UI/Basic.img/BtCancel3 |
| 3102 | SP_3102_UI_BASICIMG_BTBUDDY | UI/Basic.img/BtBuddy |
| 3103 | SP_3103_UI_BASICIMG_BTGUILD | UI/Basic.img/BtGuild |
| 3104 | SP_3104_UI_CASHSHOPIMG_CSNOTICE_2_BACKGRND | UI/CashShop.img/CSNotice/2/backgrnd |
| 3105 | SP_3105_UI_CASHSHOPIMG_CSNOTICE_3_BACKGRND | UI/CashShop.img/CSNotice/3/backgrnd |
| 3106 | SP_3106_UI_CASHSHOPIMG_CSNOTICE_5_BACKGRND | UI/CashShop.img/CSNotice/5/backgrnd |
| 3107 | SP_3107_UI_CASHSHOPIMG_CSWEBMSG_BACKGRND | UI/CashShop.img/CSWebMsg/backgrnd |
| 3108 | SP_3108_UI_CASHSHOPIMG_CSWEBMSG_BACKGRND1 | UI/CashShop.img/CSWebMsg/backgrnd1 |
| 3109 | SP_3109_PLEASE_ENTER_THE_NAME_OF__R_NTHE_RECEIVING_CHARACTER | Please enter the name of \r\nthe receiving character. |
| 3110 | SP_3110_YOU_CANNOT_SEND_GIFTS_TO_YOURSELF | You cannot send gifts to yourself. |
| 3111 | SP_3111_THE_MESSAGE_CAN_BE_NO_LONGER_THAN_72_CHARACTERS_LONG | The message can be no longer than 72 characters long. |
| 3112 | SP_3112_PLEASE_ENTER_THE_MESSAGE | Please enter the message. |
| 3113 | SP_3113_SENT_YOU_A_PRESENT | sent you a present. |
| 3114 | SP_3114_YOU_HAVE_SUCCESSFULLY_MADE_THE_PURCHASE_AT_THE_WEB_SHOP | You have successfully made the purchase at the Web Shop. |
| 3115 | SP_3115_HAS_SENT_YOUR_THIS_GIFT_THROUGH_THE_WEB_SHOP | has sent your this gift through the Web Shop. |
| 3116 | SP_3116_PLEASE_ENTER_A_MESSAGE | Please enter a message. |
| 3117 | SP_3117_ORIGINAL_PRICE__D_CASH | Original Price : %d Cash |
| 3118 | SP_3118_DISCOUNT_PRICE_ | Discount Price : |
| 3119 | SP_3119_UI_CASHSHOPIMG_CSEFFECT_EFFECT_0 | UI/CashShop.img/CSEffect/effect/0 |
| 3120 | SP_3120_UI_CASHSHOPIMG_CSEFFECT_EFFECT_1 | UI/CashShop.img/CSEffect/effect/1 |
| 3121 | SP_3121_UI_CASHSHOPIMG_CSEFFECT_S | UI/CashShop.img/CSEffect/%s |
| 3122 | SP_3122_CLASS | Class |
| 3123 | SP_3123_LIMIT | Limit |
| 3124 | SP_3124_PBCASH | PbCash |
| 3125 | SP_3125_PBPOINT | PbPoint |
| 3126 | SP_3126_PBGIFT | PbGift |
| 3127 | SP_3127_NEW | new |
| 3128 | SP_3128_SALE | sale |
| 3129 | SP_3129_UI_CASHSHOPIMG_BASE_TAB_ENABLE | UI/CashShop.img/Base/Tab/Enable |
| 3130 | SP_3130_UI_CASHSHOPIMG_BASE_TAB_DISABLE | UI/CashShop.img/Base/Tab/Disable |
| 3131 | SP_3131_UI_CASHSHOPPREVIEWIMG | UI/CashShopPreview.img |
| 3132 | SP_3132_UI_CASHSHOPIMG_BASE_PREVIEW_D | UI/CashShop.img/Base/Preview/%d |
| 3133 | SP_3133_UI_CASHSHOPIMG_BASE_PREVIEWONOFF_ON | UI/CashShop.img/Base/PreviewOnOff/On |
| 3134 | SP_3134_UI_CASHSHOPIMG_BASE_PREVIEWONOFF_OFF | UI/CashShop.img/Base/PreviewOnOff/Off |
| 3135 | SP_3135_EXECPATH | ExecPath |
| 3136 | SP_3136___MAPLESTORYEXE | \\\\MapleStory.exe |
| 3137 | SP_3137___MAPLESTORYTEXE | \\\\MapleStoryT.exe |
| 3138 | SP_3138_AVAILABLE_UNTIL_D_D_04D_AT_02D02D | Available until %d/%d/%04d at %02d:%02d |
| 3139 | SP_3139_ITEM_CASH_0501IMG_08D_EFFECT | Item/Cash/0501.img/%08d/effect |
| 3140 | SP_3140_HELLO_I_AM_THE_GM_OF_MAPLE_STORY | Hello, I am the GM of Maple Story. |
| 3141 | SP_3141_WE_ARE_MONITORING_FOR_MACRO_ASSISTED_PROGRAMS_PLEASE_STOP_HUNTING_AND_FOLLOW_THE | We are monitoring for macro-assisted programs. Please stop hunting and follow the instructions provided by the GM. |
| 3142 | SP_3142_SHOULD_YOU_FAIL_TO_COMPLY_WITH_INSTRUCTIONS_PROVIDED_BY_THE_GM_YOU_WILL_BE_REGAR | Should you fail to comply with instructions provided by the GM, you will be regarded as a macro-assisted program user. |
| 3143 | SP_3143_YOU_HAVE_BEEN_SANCTIONED_AS_A_MACRO_ASSISTED_PROGRAM_USER | You have been sanctioned as a macro-assisted program user. |
| 3144 | SP_3144_FOR_MORE_INFORMATION_PLEASE_CONTACT_OUR_CUSTOMER_SUPPORT_CENTER_AT_1588_7701 | For more information, please contact our Customer Support Center at 1588-7701. |
| 3145 | SP_3145_HAVE_A_PLEASANT_DAY_IN_THE_WORLD_OF_MAPLE_STORY | Have a pleasant day in the world of Maple Story. |
| 3146 | SP_3146_UI_UIWINDOWIMG_MACRO_BACKGRND | UI/UIWindow.img/Macro/backgrnd |
| 3147 | SP_3147_UI_UIWINDOWIMG_MACRO_BACKGRND2 | UI/UIWindow.img/Macro/backgrnd2 |
| 3148 | SP_3148_UI_UIWINDOWIMG_MACRO_BACKGRND3 | UI/UIWindow.img/Macro/backgrnd3 |
| 3149 | SP_3149_UI_UIWINDOWIMG_MACRO_BACKGRND4 | UI/UIWindow.img/Macro/backgrnd4 |
| 3150 | SP_3150_UI_UIWINDOWIMG_MACRO_NUM1_D | UI/UIWindow.img/Macro/num1/%d |
| 3151 | SP_3151_UI_UIWINDOWIMG_MACRO_NUM1_COMMA | UI/UIWindow.img/Macro/num1/comma |
| 3152 | SP_3152_UI_UIWINDOWIMG_MACRO_NUM2_S | UI/UIWindow.img/Macro/num2/%s |
| 3153 | SP_3153_UI_UIWINDOWIMG_MACRO_ADMIN_0 | UI/UIWindow.img/Macro/admin/0 |
| 3154 | SP_3154_UI_UIWINDOWIMG_MACRO_ADMIN_1 | UI/UIWindow.img/Macro/admin/1 |
| 3155 | SP_3155_UI_UIWINDOWIMG_MACRO_ADMIN_2 | UI/UIWindow.img/Macro/admin/2 |
| 3156 | SP_3156_UI_UIWINDOWIMG_MACROSKILL_BACKGRND2 | UI/UIWindow.img/MacroSkill/backgrnd2 |
| 3157 | SP_3157_UI_UIWINDOWIMG_MACROSKILL_BACKGRND3 | UI/UIWindow.img/MacroSkill/backgrnd3 |
| 3158 | SP_3158_THE_USER_CANNOT_BE_FOUND | The user cannot be found. |
| 3159 | SP_3159_YOU_CANNOT_USE_IT_ON_A_USER_THAT_ISNT_IN_THE_MIDDLE_OF_ATTACK | You cannot use it on a user that isn't in the middle of attack. |
| 3160 | SP_3160_THIS_USER_HAS_ALREADY_BEEN_TESTED_BEFORE | This user has already been tested before. |
| 3161 | SP_3161_THIS_USER_IS_CURRENTLY_GOING_THROUGH_THE_LIE_DETECTOR_TEST | This user is currently going through the Lie Detector Test. |
| 3162 | SP_3162_THANK_YOU_FOR_COOPERATING_WITH_THE_LIE_DETECTOR_TEST_YOULL_BE_REWARDED_5000_MESO | Thank you for cooperating with the Lie Detector Test. You'll be rewarded 5000 mesos for not botting. |
| 3163 | SP_3163_THE_LIE_DETECTOR_TEST_CONFIRMS_THAT_YOU_HAVE_BEEN_BOTTING_REPEATED_FAILURE_OF_TH | The Lie Detector Test confirms that you have been botting. Repeated failure of the test will result in game restrictions. |
| 3164 | SP_3164_CHARACTER_AFTERIMAGE_HITIMG_ANNIVERSARY2 | Character/Afterimage/hit.img/anniversary2 |
| 3165 | SP_3165_S_I_AM_THE_GM_OF_MAPLE_STORY_WE_WILL_NOW_BEGIN_MONITORING_FOR_A_MACRO_ASSISTED_P | %s, I am the GM of Maple Story. We will now begin monitoring for a macro-assisted program. Please stop hunting and follow the instructions. Should you evade or fail to respond, you will be regarded as a macro-assisted program user and be sanctioned accordingly. |
| 3166 | SP_3166_I_AM_THE_GM_OF_MAPLE_STORY_S_GM_MONITORING_IS_ABOUT_TO_BEGIN_PLEASE_CHECK_THE_ME | I am the GM of Maple Story. %s, GM monitoring is about to begin. Please check the message above. |
| 3167 | SP_3167_S_HAVE_USED_THE_LIE_DETECTOR_TEST | %s have used the Lie Detector Test. |
| 3168 | SP_3168_STHE_SCREENSHOT_HAS_BEEN_SAVED_YOU_HAVE_BEEN_NOTIFIED_OF_MACRO_ASSISTED_PROGRAM_ | %s_The screenshot has been saved. You have been notified of macro-assisted program monitoring. |
| 3169 | SP_3169_STHE_SCREENSHOT_HAS_BEEN_SAVED_THE_LIE_DETECTOR_HAS_BEEN_ACTIVATED | %s_The screenshot has been saved. The Lie Detector has been activated. |
| 3170 | SP_3170_SYOU_HAVE_PASSED_THE_LIE_DETECTOR_TEST | %s_You have passed the Lie Detector Test. |
| 3171 | SP_3171_STHE_SCREENSHOT_HAS_BEEN_SAVED_IT_APPEARS_THAT_YOU_MAY_BE_USING_A_MACRO_ASSISTED | %s_The screenshot has been saved. It appears that you may be using a macro-assisted program. |
| 3172 | SP_3172_YOU_CANNOT_USE_IT_IN_THE_MIDDLE_OF_THE_LIE_DETECTOR_TEST | You cannot use it in the middle of the Lie Detector Test. |
| 3173 | SP_3173_YOU_MAY_NOT_USE_THE_LIE_DETECTOR_ON_THIS_AREA | You may not use the Lie Detector on this area. |
| 3174 | SP_3174_UI_CASHSHOPIMG_BASE_TAB2_ENABLE | UI/CashShop.img/Base/Tab2/Enable |
| 3175 | SP_3175_UI_CASHSHOPIMG_BASE_TAB2_DISABLE | UI/CashShop.img/Base/Tab2/Disable |
| 3176 | SP_3176_YOUR_LEVEL_IS_TOO_LOW_TO_USE_THE_SELECTED_ITEM | Your level is too low to use the selected item. |
| 3177 | SP_3177_YOUR_LEVEL_IS_TOO_HIGH_TO_USE_THE_SELECTED_ITEM | Your level is too high to use the selected item. |
| 3178 | SP_3178_THE_USER_HAS_FAILED_THE_LIE_DETECTOR_TEST_YOULL_BE_REWARDED_7000_MESOS_FROM_THE_ | The user has failed the Lie Detector Test. You'll be rewarded 7000 mesos from the user. |
| 3179 | SP_3179_YOU_HAVE_SUCCESFULLY_PASSED_THE_LIE_DETECTOR_TEST_THANK_YOU_FOR_PARTICIPATING | You have succesfully passed the Lie Detector Test. Thank you for participating! |
| 3180 | SP_3180_YOU_WILL_BE_SANCTIONED_FOR_USING_A_MACRO_ASSISTED_PROGRAM | You will be sanctioned for using a macro-assisted program. |
| 3181 | SP_3181_YOU_CANNOT_USE_THE_MYSTIC_DOOR_SKILL_NEAR_THE_PORTAL | You cannot use the Mystic Door Skill near the portal. |
| 3182 | SP_3182_UI_LOGINIMG_WORLDNOTICE_BALLOONFORLOGINPROCESS | UI/Login.img/WorldNotice/BalloonForLoginProcess |
| 3183 | SP_3183_UI_LOGINIMG_WORLDNOTICE_BALLOON | UI/Login.img/WorldNotice/Balloon |
| 3184 | SP_3184_UI_LOGINIMG_WORLDNOTICE | UI/Login.img/WorldNotice |
| 3185 | SP_3185_ONLY | only |
| 3186 | SP_3186_TRADEBLOCK | tradeBlock |
| 3187 | SP_3187_SCANTRADEBLOCK | scanTradeBlock |
| 3188 | SP_3188_TRADEAVAILABLE | tradeAvailable |
| 3189 | SP_3189_NOTSALE | notSale |
| 3190 | SP_3190_UI_UIWINDOWIMG_QUESTICON_D | UI/UIWindow.img/QuestIcon/%d |
| 3191 | SP_3191__R_N_R_NFUI_UIWINDOWIMG_UTILDLGEX_LIST1_R_N | \r\n\r\n#fUI/UIWindow.img/UtilDlgEx/list1#\r\n |
| 3192 | SP_3192__R_N_R_NFUI_UIWINDOWIMG_UTILDLGEX_LIST0_R_N | \r\n\r\n#fUI/UIWindow.img/UtilDlgEx/list0#\r\n |
| 3193 | SP_3193__R_N_R_NFUI_UIWINDOWIMG_UTILDLGEX_LIST3_R_N | \r\n\r\n#fUI/UIWindow.img/UtilDlgEx/list3#\r\n |
| 3194 | SP_3194__R_N_R_NFUI_UIWINDOWIMG_UTILDLGEX_LIST2_R_N | \r\n\r\n#fUI/UIWindow.img/UtilDlgEx/list2#\r\n |
| 3195 | SP_3195_DLD_SLK_R_N | #d#L%d# %s#l#k\r\n |
| 3196 | SP_3196_ETC_SCRIPTINFOIMG | Etc/ScriptInfo.img |
| 3197 | SP_3197_DD | d%d |
| 3198 | SP_3198_QUEST_ACTIMG_D_D | Quest/Act.img/%d/%d |
| 3199 | SP_3199_QUEST_ACTIMG_D_D_ITEM | Quest/Act.img/%d/%d/item |
| 3200 | SP_3200_QUEST_ACTIMG_D_D_SKILL | Quest/Act.img/%d/%d/skill |
| 3201 | SP_3201_JOB | job |
| 3202 | SP_3202__R_NBLD_I07D_S_DL | \r\n#b#L%d# #i%07d# %s %d개#l |
| 3203 | SP_3203__R_N_R_NFUI_UIWINDOWIMG_QUESTICON_3_0_R_N | \r\n\r\n#fUI/UIWindow.img/QuestIcon/3/0#\r\n |
| 3204 | SP_3204_LDVD_TD_DL_R_N | #L%d##v%d:# #t%d:# %d#l\r\n |
| 3205 | SP_3205__R_N_R_NFUI_UIWINDOWIMG_QUESTICON_4_0_R_N | \r\n\r\n#fUI/UIWindow.img/QuestIcon/4/0#\r\n |
| 3206 | SP_3206_VD_TD_D_R_N | #v%d:# #t%d:# %d\r\n |
| 3207 | SP_3207_S07D_Q07D__R_N | #s%07d:# #q%07d:# \r\n |
| 3208 | SP_3208_FUI_UIWINDOWIMG_QUESTICON_7_0_D_MESO__R_N | #fUI/UIWindow.img/QuestIcon/7/0# %d Meso\\\r\n |
| 3209 | SP_3209_FUI_UIWINDOWIMG_QUESTICON_8_0_D_EXP_R_N | #fUI/UIWindow.img/QuestIcon/8/0# %d exp\r\n |
| 3210 | SP_3210_FUI_UIWINDOWIMG_QUESTICON_6_0_D_R_N | #fUI/UIWindow.img/QuestIcon/6/0# %d\r\n |
| 3211 | SP_3211_FUI_UIWINDOWIMG_QUESTICON_5_0_R_N | #FUI/UIWindow.img/QuestIcon/5/0#\r\n |
| 3212 | SP_3212_QUEST_SAYIMG | Quest/Say.img |
| 3213 | SP_3213_QUEST_ACTIMG_D_1 | Quest/Act.img/%d/1 |
| 3214 | SP_3214_ASK | ask |
| 3215 | SP_3215_STOP | stop |
| 3216 | SP_3216_ORDER | order |
| 3217 | SP_3217_ITEM | item |
| 3218 | SP_3218_QUEST_SAYIMG_D_1_LOST | Quest/Say.img/%d/1/lost |
| 3219 | SP_3219_QUEST_SAYIMG_D_1_STOP_S | Quest/Say.img/%d/1/stop/%s |
| 3220 | SP_3220_QUEST_CHECKIMG | Quest/Check.img |
| 3221 | SP_3221_ENDMESO | endmeso |
| 3222 | SP_3222_LVMIN | lvmin |
| 3223 | SP_3223_LVMAX | lvmax |
| 3224 | SP_3224_WORLDMIN | worldmin |
| 3225 | SP_3225_WORLDMAX | worldmax |
| 3226 | SP_3226_POP | pop |
| 3227 | SP_3227_STATE | state |
| 3228 | SP_3228_QUEST_ACTIMG | Quest/Act.img |
| 3229 | SP_3229_PERIOD | period |
| 3230 | SP_3230_GENDER | gender |
| 3231 | SP_3231_VAR | var |
| 3232 | SP_3232_QUEST_QUESTINFOIMG | Quest/QuestInfo.img |
| 3233 | SP_3233_UI_UIWINDOWIMG_UTILDLGEX_BTQYES | UI/UIWindow.img/UtilDlgEx/BtQYes |
| 3234 | SP_3234_UI_UIWINDOWIMG_UTILDLGEX_BTQGIVEUP | UI/UIWindow.img/UtilDlgEx/BtQGiveup |
| 3235 | SP_3235_UI_UIWINDOWIMG_UTILDLGEX_LISTD | UI/UIWindow.img/UtilDlgEx/list%d |
| 3236 | SP_3236_PARENT | parent |
| 3237 | SP_3237_NO_LIMIT | No limit |
| 3238 | SP_3238_OVER_LEVEL_D | Over Level %d |
| 3239 | SP_3239_S_UNDER_LEVEL_D | %s Under Level %d |
| 3240 | SP_3240_AVAILABLE_TO_ALL | Available to all |
| 3241 | SP_3241_ALL_USERS_EXCEPT_BEGINNERS | All users except beginners. |
| 3242 | SP_3242_AVALIABLE_TO_ALL_EXCEPT_S | Avaliable to all except %s |
| 3243 | SP_3243_DTH_JOB_ADVANCEMENT | %dth job advancement |
| 3244 | SP_3244_1ST_JOB_ADVANCEMENT | 1st job advancement |
| 3245 | SP_3245_2ND_JOB_ADVANCEMENT | 2nd job advancement |
| 3246 | SP_3246_3RD_JOB_ADVANCEMENT | 3rd job advancement |
| 3247 | SP_3247_4TH_JOB_ADVANCEMENT | 4th job advancement |
| 3248 | SP_3248_AT_LEAST_AT_DTH_JOB_ADVANCEMENT | At least at %dth job advancement |
| 3249 | SP_3249_AT_LEAST_AT_1ST_JOB_ADVANCEMENT | At least at 1st job advancement |
| 3250 | SP_3250_AT_LEAST_AT_2ND_JOB_ADVANCEMENT | At least at 2nd job advancement |
| 3251 | SP_3251_AT_LEAST_AT_3RD_JOB_ADVANCEMENT | At least at 3rd job advancement |
| 3252 | SP_3252_AT_LEAST_AT_4TH_JOB_ADVANCEMENT | At least at 4th job advancement |
| 3253 | SP_3253_ENDED_AT_D_D_D_AT_02D00 | Ended at %d/%d/%d at %02d:00 |
| 3254 | SP_3254_BTQYES | BtQYes |
| 3255 | SP_3255_BTQNO | BtQNo |
| 3256 | SP_3256_QUEST_SAYIMG_D_D_YES | Quest/Say.img/%d/%d/yes |
| 3257 | SP_3257_THE_QUEST_HAS_ENDED_R_NDUE_TO_AN_UNKNOWN_ERROR | The quest has ended\r\ndue to an unknown error. |
| 3258 | SP_3258_ARE_YOU_SURE_YOU_WANT_TO_QUIT | Are you sure you want to quit? |
| 3259 | SP_3259_S_ITEM_INVENTORY_IS_FULL | %s item inventory is full. |
| 3260 | SP_3260__OR |  or |
| 3261 | SP_3261_MONEY | money |
| 3262 | SP_3262_NEXTQUEST | nextQuest |
| 3263 | SP_3263_MASTER | Master |
| 3264 | SP_3264_JRMASTER | Jr.Master |
| 3265 | SP_3265_MEMBER | Member |
| 3266 | SP_3266__12S____11S___3D____10S | %-12s   %-11s   %3d   %-10s |
| 3267 | SP_3267__12S____11S___3S____10S | %-12s   %-11s   %3s   %-10s |
| 3268 | SP_3268_DHRS | %dhrs. |
| 3269 | SP_3269_DMIN | \t%dmin. |
| 3270 | SP_3270_YOU_HAVE_SS_REMAINING_ON_THIS_INTERNET_CAFE | You have %s%s remaining on this Internet Cafe |
| 3271 | SP_3271_YOU_HAVE_SS_REMAINING_ON_THIS_INTERNET_CAFE_CURRENT__OF_USERS_D_THE_GAME_WILL_EN | You have %s%s remaining on this Internet Cafe (Current # of users :%d) The game will end after the time expires. |
| 3272 | SP_3272_NOTATTACK | notAttack |
| 3273 | SP_3273_UI_UIWINDOWIMG_UTILDLGEX_NOTICE | UI/UIWindow.img/UtilDlgEx/notice |
| 3274 | SP_3274_THIS_IS_THE_NX_COUPON_NUMBER_R_NREGISTER_YOUR_COUPON_AT_WWWNEXONNET | This is the NX coupon number.\r\nRegister your coupon at www.nexon.net. |
| 3275 | SP_3275_THIS_COUPON_IS_ONLY_AVAILABLE_TO_THE_USERS_BUYING_CASH_ITEM_FOR_THE_FIRST_TIME | This coupon is only available to the users buying cash item for the first time. |
| 3276 | SP_3276_NOW_I_AM_ASKING_OTHER_GUILD_MEMBERS_FOR_THE_AGREEMENT_OF_FORMING_A_GUILD__PLEASE | Now I am asking other guild members for the agreement of forming a guild~  Please hold on~... |
| 3277 | SP_3277_SOMEBODY_HAS_DISAGREED_TO_FORM_A_GUILD__PLEASE_COME_BACK_TO_ME_WHEN_YOU_MEET_WIT | Somebody has disagreed to form a guild...  Please come back to me when you meet with the right people...  You can only make a guild after getting agreement from all... |
| 3278 | SP_3278_THE_NAME_IS_ALREADY_IN_USE_PLEASE_TRY_OTHER_ONES | The name is already in use... Please try other ones.... |
| 3279 | SP_3279_CONGRATULATION__S_GUILD_HAS_BEEN_REGISTERED_TO_OUR_GUILD_OFFICE__I_WISH_THE_BEST | Congratulation~  %s guild has been registered to our guild office...  I wish the best luck for you guys~ |
| 3280 | SP_3280_THE_GUILD_HAS_BEEN_DISBANDED__PLEASE_COME_BACK_TO_ME_WHEN_YOU_WANT_TO_MAKE_A_GUI | The guild has been disbanded..  Please come back to me when you want to make a guild, again.. |
| 3281 | SP_3281_CONGRATULATION__THE_NUMBER_OF_GULD_MEMBERS_HAS_NOW_INCREASED_TO_D___PLEASE_COME_ | Congratulation~  The number of guld members has now increased to %d ...  Please come back to me whenever you need to.. |
| 3282 | SP_3282_THE_PROBLEM_HAS_HAPPENED_DURING_THE_PROCESS_OF_GATHERING_AGREEMENT_OF_FORMING_TH | The problem has happened during the process of gathering agreement of forming the guild... Plese try again later... |
| 3283 | SP_3283_THE_PROBLEM_HAS_HAPPENED_DURING_THE_PROCESS_OF_FORMING_THE_GUILD_PLESE_TRY_AGAIN | The problem has happened during the process of forming the guild... Plese try again later... |
| 3284 | SP_3284_THE_PROBLEM_HAS_HAPPENED_DURING_THE_PROCESS_OF_DISBANDING_THE_GUILD_PLESE_TRY_AG | The problem has happened during the process of disbanding the guild... Plese try again later... |
| 3285 | SP_3285_THE_PROBLEM_HAS_HAPPENED_DURING_THE_PROCESS_OF_INCREASING_THE_GUILD_PLESE_TRY_AG | The problem has happened during the process of increasing the guild... Plese try again later... |
| 3286 | SP_3286_THE_ITEM_ALREADY_HAS_A_PRINTED_NAME_ON_IT | The item already has a printed name on it.. |
| 3287 | SP_3287_WOULID_LIKE_TO_PRINT_YOUR_NAME_ON_S | Woulid like to print your name on %s? |
| 3288 | SP_3288_UI_UIWINDOWIMG_MOBGAGE_BACKGRND | UI/UIWindow.img/MobGage/backgrnd |
| 3289 | SP_3289_UI_UIWINDOWIMG_MOBGAGE_BACKGRND2 | UI/UIWindow.img/MobGage/backgrnd2 |
| 3290 | SP_3290_UI_UIWINDOWIMG_MOBGAGE_BACKGRND3 | UI/UIWindow.img/MobGage/backgrnd3 |
| 3291 | SP_3291_UI_UIWINDOWIMG_MOBGAGE_BACKGRND4 | UI/UIWindow.img/MobGage/backgrnd4 |
| 3292 | SP_3292_UI_UIWINDOWIMG_MOBGAGE_MOB_D | UI/UIWindow.img/MobGage/Mob/%d |
| 3293 | SP_3293_UI_UIWINDOWIMG_MOBGAGE_GAGE_D_0 | UI/UIWindow.img/MobGage/Gage/%d/0 |
| 3294 | SP_3294_UI_UIWINDOWIMG_MOBGAGE_GAGE_D_1 | UI/UIWindow.img/MobGage/Gage/%d/1 |
| 3295 | SP_3295__S_S_POSITION_HAS_BEEN_CHANGED_TO__S_ | [%s]'s position has been changed to [%s]. |
| 3296 | SP_3296_IT_IS_SAVED | It is saved. |
| 3297 | SP_3297_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDGRADE | UI/UIWindow.img/UserList/Guild/GuildGrade |
| 3298 | SP_3298_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDEDIT | UI/UIWindow.img/UserList/Guild/GuildEdit |
| 3299 | SP_3299_PLEASE_PUT_A_POSITION_R_N_WHICH_YOU_WANT_TO_CHANGE_TO | Please put a position,\r\n which you want to change to. |
| 3300 | SP_3300_YOU_CANNOT_DELETE_THIS_POSITION_BECAUSE_R_NTHERE_IS_A_LOWER_RANK_THAN_THIS_POSIT | You cannot delete this position, because\r\nthere is a lower rank than this position. |
| 3301 | SP_3301_YOU_CANNOT_DELETE_THIS_POSITION_R_NBECAUSE_THERE_ARE_STILL_MEMBERS_R_NWHO_ARE_IN | You cannot delete this position,\r\nbecause there are still members\r\nwho are in this position. |
| 3302 | SP_3302_WOULD_LIKE_TO_SAVE_THE_CHANGE | Would like to save the change? |
| 3303 | SP_3303_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDEDIT_BTMODF | UI/UIWindow.img/UserList/Guild/GuildEdit/BtModf |
| 3304 | SP_3304_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDEDIT_BTSAVE | UI/UIWindow.img/UserList/Guild/GuildEdit/BtSave |
| 3305 | SP_3305_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_GUILDINFO5 | UI/UIWindow.img/UserList/Guild/GuildInfo/guildinfo5 |
| 3306 | SP_3306_SOUND_CASHEFFECTIMG_08D_HIT | Sound/CashEffect.img/%08d/Hit |
| 3307 | SP_3307_SWIM | swim |
| 3308 | SP_3308_FLOW | flow |
| 3309 | SP_3309_FATIGUE | fatigue |
| 3310 | SP_3310_EFFECT_MAPEFFIMG_S | Effect/MapEff.img/%s |
| 3311 | SP_3311_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_BTAGREE | UI/UIWindow.img/UserList/Guild/MakeMark/BtAgree |
| 3312 | SP_3312_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_BTDISAGREE | UI/UIWindow.img/UserList/Guild/MakeMark/BtDisagree |
| 3313 | SP_3313_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_BTLEFT | UI/UIWindow.img/UserList/Guild/MakeMark/BtLeft |
| 3314 | SP_3314_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_BTRIGHT | UI/UIWindow.img/UserList/Guild/MakeMark/BtRight |
| 3315 | SP_3315_______ANIMAL |       Animal |
| 3316 | SP_3316_______PLANT |       Plant |
| 3317 | SP_3317_______PATTERN |       Pattern |
| 3318 | SP_3318_______CHARACTER |       Character |
| 3319 | SP_3319_______ETC |       ETC |
| 3320 | SP_3320_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_BACKGRND | UI/UIWindow.img/UserList/Guild/MakeMark/backgrnd |
| 3321 | SP_3321_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_MESSAGE0 | UI/UIWindow.img/UserList/Guild/MakeMark/message0 |
| 3322 | SP_3322_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_MESSAGE1 | UI/UIWindow.img/UserList/Guild/MakeMark/message1 |
| 3323 | SP_3323_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_MESSAGE2 | UI/UIWindow.img/UserList/Guild/MakeMark/message2 |
| 3324 | SP_3324_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_MESSAGE3 | UI/UIWindow.img/UserList/Guild/MakeMark/message3 |
| 3325 | SP_3325_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_MESSAGE4 | UI/UIWindow.img/UserList/Guild/MakeMark/message4 |
| 3326 | SP_3326_UI_UIWINDOWIMG_USERLIST_GUILD_MAKEMARK_INNER | UI/UIWindow.img/UserList/Guild/MakeMark/inner |
| 3327 | SP_3327_UI_GUILDMARKIMG_ | UI/GuildMark.img/ |
| 3328 | SP_3328_BACKGROUND | BackGround |
| 3329 | SP_3329_MARK_ANIMAL | Mark/Animal |
| 3330 | SP_3330_MARK_PLANT | Mark/Plant |
| 3331 | SP_3331_MARK_PATTERN | Mark/Pattern |
| 3332 | SP_3332_MARK_LETTER | Mark/Letter |
| 3333 | SP_3333_MARK_ETC | Mark/Etc |
| 3334 | SP_3334_ANIMAL | Animal |
| 3335 | SP_3335_PATTERN | Pattern |
| 3336 | SP_3336_LETTER | Letter |
| 3337 | SP_3337_UI_GUILDMARKIMG_MARK_S_08D_D | UI/GuildMark.img/Mark/%s/%08d/%d |
| 3338 | SP_3338_UI_GUILDMARKIMG_BACKGROUND_08D_D | UI/GuildMark.img/BackGround/%08d/%d |
| 3339 | SP_3339_WOULD_YOU_LIKE_TO_SAVE_IT_AS_A_GUILD_EMBLEM | Would you like to save it as a guild emblem? |
| 3340 | SP_3340_IT_IS_COMPLETED | It is completed. |
| 3341 | SP_3341_UI_UIWINDOWIMG_USERLIST_FRIEND_BTWHERE | UI/UIWindow.img/UserList/Friend/BtWhere |
| 3342 | SP_3342_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTWHERE | UI/UIWindow.img/UserList/Guild/GuildInfo/BtWhere |
| 3343 | SP_3343_YOU_CANNOT_MOVE_THAT_CHANNEL_PLEASE_TRY_AGAIN_LATER | You cannot move that channel. Please try again later. |
| 3344 | SP_3344_YOU_CANNOT_GO_INTO_THE_CASH_SHOP_PLEASE_TRY_AGAIN_LATER | You cannot go into the cash shop. Please try again later. |
| 3345 | SP_3345_THE_CASH_SHOP_IS_NOT_AVAILABLE_IN_THE_TRIAL_VERSION | The Cash Shop is not available in the Trial Version. |
| 3346 | SP_3346_DOWNLOAD_THE_FULL_CLIENT_TO_CUSTOMIZE_YOUR_CHARACTER | Download the Full Client to customize your character! |
| 3347 | SP_3347__S__QUEST_HAS_BEEN_EXPIRED_THE_QUEST_HAS_BEEN_DELETED_FROM_THE_QUEST_LIST | [%s] quest has been expired. The quest has been deleted from the quest list. |
| 3348 | SP_3348__GUILD_NOTICE | * Guild Notice |
| 3349 | SP_3349__GUILD_NOTICE__S | * Guild Notice : %s |
| 3350 | SP_3350_PLEASE_ENTER_THE_GUILD_NOTICE | Please enter the Guild Notice. |
| 3351 | SP_3351_GAMEGUARD_ERROR_INITIALIZING_D_R_N_R_N | GameGuard: Error Initializing (%d)\r\n\r\n |
| 3352 | SP_3352_GAMEGUARD_IS_ALREADY_RUNNING_PLEASE_REBOOT_AND_TRY_AGAIN | GameGuard is already running. Please reboot and try again. |
| 3353 | SP_3353_THE_CLIENT_HAS_EXECUTED_MULTIPLE_TIMES_OR_GAMEGUARD_IS_ALREADY_OPEN_PLEASE_CLOSE | The client has executed multiple times, or GameGuard is already open. Please close the game, and restart the client. |
| 3354 | SP_3354_AN_ERROR_OCCURED_WHILE_RESETTING_GAMEGUARD_PLEASE_TRY_REBOOTING_THE_SYSTEM_BEFOR | An error occured while resetting GameGuard. Please try rebooting the system before restarting the client, or close all programs that may potentially collide with the client before restarting. We also recommend running virus/spyware checks for your system. |
| 3355 | SP_3355_A_GAMEGUARD_FILE_IS_CORRUPT_OR_MISSING_WE_RECOMMEND_EXECUTING_THE_GAMEGUARD_SETU | A GameGuard file is corrupt or missing. We recommend executing the GameGuard setup file for solution. |
| 3356 | SP_3356_A_WINDOWS_SYSTEM_FILE_IS_CORRUPT_WE_RECOMMEND_VIRUS_CHECK_TO_YOUR_SYSTEM_AS_WELL | A Windows system file is corrupt. We recommend virus check to your system, as well as upgrading/reinstalling Internet Explorer for solution. |
| 3357 | SP_3357_AN_ERROR_OCCURED_WHILE_EXECUTING_GAMEGUARD_PLEASE_RE_INSTALL_THE_GAMEGUARD_SETUP | An error occured while executing GameGuard. Please re-install the GameGuard setup file, then restart the client. |
| 3358 | SP_3358_A_HACKING_PROGRAM_HAS_BEEN_DETECTED_PLEASE_CLOSE_ALL_UNNECESSARY_PROGRAMS_BEFORE | A hacking program has been detected. Please close all unnecessary programs before re-opening the game. For further assistance, please  find the *.erl files that are located in the GameGuard Folder of the MapleStory folder, and email them to game@inca.co.kr as attachments. |
| 3359 | SP_3359_YOU_HAVE_PRESSED_CANCEL_DURING_THE_UPDATE_IF_THE_CANCELLATION_IS_DUE_TO_A_LACK_O | You have pressed Cancel during the update. If the cancellation is due to a lack of connection, then check the network connection, the firewall settings, or simply try again. |
| 3360 | SP_3360_THE_COLLISION_HAS_BEEN_DETECTED_BETWEEN_THE_PROGRAM_INSTALLED_ON_YOUR_COMPUTER_A | The collision has been detected between the program installed on your computer and Gameguard. |
| 3361 | SP_3361_UNABLE_TO_CONNECT_TO_THE_GAMEGUARD_UPDATE_SERVER_PLEASE_TRY_RECONNECTING_LATER_O | Unable to connect to the GameGuard update server. Please try reconnecting later, or check the network connection. |
| 3362 | SP_3362_AN_ERROR_OCCURED_DURING_THE_UPDATE_PLEASE_TRY_TEMPORARILY_DISABLING_THE_VIRUS_VA | An error occured during the update. Please try temporarily disabling the Virus Vaccine and restart the client, or change the system option, if you're using the PC-maintenance program. |
| 3363 | SP_3363_AN_ERROR_OCCURED_WHILE_LOADING_VIRUS___HACKING_TOOL_SEARCH_THIS_MAY_OCCUR_DUE_TO | An error occured while loading Virus / Hacking Tool search. This may occur due to lack of memory or virus infection. |
| 3364 | SP_3364_AN_ERROR_OCCURRED_WHILE_PROCESSING_GAMEGUARD_FOR_FURTHER_ASSISTANCE_PLEASE__FIND | An error occurred while processing GameGuard. For further assistance, please  find the *.erl files that are located in the GameGuard Folder of the MapleStory folder, and email them to game@inca.co.kr as attachments. |
| 3365 | SP_3365_A_HACKING_PROGRAM_HAS_BEEN_DETECTED_IF_YOU_FEEL_THAT_THIS_ERROR_IS_A_MISTAKE_PLE | A hacking program has been detected. If you feel that this error is a mistake, please  find the *.erl files that are located in the GameGuard Folder of the MapleStory folder, and email them to game@inca.co.kr as attachments. |
| 3366 | SP_3366_HAS_RECEIVED_A_GIFT | has received a gift. |
| 3367 | SP_3367_S_FAME_HAS_GONE_UP_1 | 's fame has gone up +1. |
| 3368 | SP_3368_UI_UIWINDOWIMG_CLAIM_BACKGRND | UI/UIWindow.img/Claim/backgrnd |
| 3369 | SP_3369_UI_UIWINDOWIMG_CLAIM_NOTICE | UI/UIWindow.img/Claim/notice |
| 3370 | SP_3370_UI_UIWINDOWIMG_CLAIM_BTCLAIM | UI/UIWindow.img/Claim/BtClaim |
| 3371 | SP_3371_UI_UIWINDOWIMG_CLAIM_BTCANCEL | UI/UIWindow.img/Claim/BtCancel |
| 3372 | SP_3372_UI_UIWINDOWIMG_CLAIM_BTCCLAIM | UI/UIWindow.img/Claim/BtCClaim |
| 3373 | SP_3373_UI_UIWINDOWIMG_CLAIM_BTPCLAIM | UI/UIWindow.img/Claim/BtPClaim |
| 3374 | SP_3374_CURSE_INAPPROPRIATE_CONTENT | Curse/Inappropriate Content |
| 3375 | SP_3375_FRAUD | Fraud |
| 3376 | SP_3376_CASH_TRADE | Cash Trade |
| 3377 | SP_3377_IMPERSONATING_GM | Impersonating GM |
| 3378 | SP_3378_EXPOSING_PERSONAL_INFO | Exposing Personal Info |
| 3379 | SP_3379_REPORT_USE_OF_ILLEGAL_PROGRAMS | Report Use of Illegal Programs |
| 3380 | SP_3380_YOU_HAVE_SUCCESSFULLY_REGISTERED | You have successfully registered. |
| 3381 | SP_3381_YOUR_REPORT_HAS_BEEN_SUCCESSFULLY_REGISTERED_R_NYOU_HAVE_D_REPORTS_LEFT_THIS_WEE | Your report has been successfully registered.\r\nYou have %d reports left this week. |
| 3382 | SP_3382_YOUR_REPORT_HAS_BEEN_SUCCESSFULLY_REGISTERED_R_NYOU_HAVE_NO_REPORT_LEFT_THIS_WEE | Your report has been successfully registered.\r\nYou have no report left this week. |
| 3383 | SP_3383_THE_REPORT_HAS_BEEN_SUCCESSFULLY_MADE_R_NYOU_MAY_NOT_REPORT_FOR_THE_REST_OF_THE_ | The report has been successfully made.\r\nYou may not report for the rest of the day. |
| 3384 | SP_3384_YOU_HAVE_BEEN_REPORTED_BY_A_USER | You have been reported by a user. |
| 3385 | SP_3385_THE_REPORTING_SYSTEM_IS_CURRENTLY_UNDERGOING_MAINTENENCE_CHECK | The reporting system is currently undergoing maintenence check. |
| 3386 | SP_3386_PLEASE_TRY_AGAIN_LATER | Please try again later. |
| 3387 | SP_3387_PLEASE_RE_CHECK_THE_CHARACTER_NAME_THEN_TRY_AGAIN | Please re-check the character name, then try again. |
| 3388 | SP_3388_YOU_DO_NOT_HAVE_ENOUGH_MESOS_TO_REPORT | You do not have enough mesos to report. |
| 3389 | SP_3389_UNABLE_TO_CONNECT_TO_THE_SERVER | Unable to connect to the server. |
| 3390 | SP_3390_YOU_HAVE_EXCEEDED_R_NTHE_NUMBER_OF_REPORTS_AVAILABLE | You have exceeded\r\nthe number of reports available. |
| 3391 | SP_3391_THERES_NO_CHARACTER_CAN_BE_REPORTED_IN_THIS_MAP | There's no character can be reported in this map. |
| 3392 | SP_3392_WE_ARE_CURRENTLY_ON_THE_TESTING_PERIOD_N_NWHICH_MEANS_THE_SERVICE_MAY_CONCLUDE_W | We are currently on the testing period,\n\nwhich means the service may conclude without notice. |
| 3393 | SP_3393_YOUR_CHATTING_WILL_BE_ENABLED_AFTER_04D_D_D | Your chatting will be enabled after %04d/%d/%d. |
| 3394 | SP_3394_PLEASE_ENTER_THE_REASON_FOR_REPORTING | Please enter the reason for reporting. |
| 3395 | SP_3395_YOU_MAY_ONLY_REPORT_FROM_D_TO_D | You may only report from %d to %d. |
| 3396 | SP_3396_THIS_CHARACTER_IS_UNABLE_TO_REPORT_OTHERS | This character is unable to report others. |
| 3397 | SP_3397_UNABLE_TO_REPORT_DUE_TO_N_NPREVIOUSLY_BEING_CITED_FOR_A_FALSE_REPORT | Unable to report due to\n\npreviously being cited for a false report. |
| 3398 | SP_3398_PLEASE_SELECT | Please select. |
| 3399 | SP_3399_PLEASE_SELECT_THE_CHARACTER_YOUD_LIKE_TO_REPORT | Please select the character you'd like to report. |
| 3400 | SP_3400_ETC_CHATBLOCKREASONIMG | Etc/ChatBlockReason.img |
| 3401 | SP_3401_HAHA | haha |
| 3402 | SP_3402_HOHO | hoho |
| 3403 | SP_3403_HEHE | hehe |
| 3404 | SP_3404_HARHAR | harhar |
| 3405 | SP_3405_WAHAHA | wahaha |
| 3406 | SP_3406_SOB | sob |
| 3407 | SP_3407_TEARS | tears |
| 3408 | SP_3408_SNIFF | sniff |
| 3409 | SP_3409_PETCONSUMEITEMID | PetConsumeItemID |
| 3410 | SP_3410_PETCONSUMEHPITEMID | PetConsumeHPItemID |
| 3411 | SP_3411_PETCONSUMEMPITEMID | PetConsumeMPItemID |
| 3412 | SP_3412_CHASE | chase |
| 3413 | SP_3413_S_TEAMS_SNOWBALL_HAS_PASSED_THE_D_STAGE | %s Team's snowball has passed the %d stage. |
| 3414 | SP_3414_S_TEAM_IS_ATTACKING_THE_SNOWMAN_STOPPING_THE_PROGRESS_OF_S_TEAMS_SNOWBALL | %s Team is attacking the snowman, stopping the progress of %s Team's snowball. |
| 3415 | SP_3415_S_TEAMS_SNOWBALL_IS_MOVING_AGAIN | %s Team's snowball is moving again. |
| 3416 | SP_3416_SEALED_ITEMS_CANNOT_BE_R_NSOLD_TRADED_OR_DROPPED | Sealed items cannot be\r\nsold, traded, or dropped. |
| 3417 | SP_3417_THIS_ITEM_CANNOT_BE_SEALED | This item cannot be sealed. |
| 3418 | SP_3418_PLEASE_SELECT_THE_ITEM_YOUD_LIKE_TO_SEAL | Please select the item you'd like to seal. |
| 3419 | SP_3419_THIS_ITEM_CANNOT_BE_USED_R_NONCE_IT_IS_SEALED | This item cannot be used\r\nonce it is sealed. |
| 3420 | SP_3420_S_R_NWOULD_YOU_LIKE_TO_SEAL_THIS_ITEM | %s\r\nWould you like to seal this item? |
| 3421 | SP_3421_UI_UIWINDOWIMG_ITEMPROTECTOR_BACKGRND | UI/UIWindow.img/ItemProtector/backgrnd |
| 3422 | SP_3422_UI_UIWINDOWIMG_ITEMPROTECTOR_BTOK | UI/UIWindow.img/ItemProtector/BtOk |
| 3423 | SP_3423_UI_UIWINDOWIMG_ITEMPROTECTOR_BTCANCEL | UI/UIWindow.img/ItemProtector/BtCancel |
| 3424 | SP_3424_UI_UIWINDOWIMG_ITEMPROTECTOR_ICON_0 | UI/UIWindow.img/ItemProtector/Icon/0 |
| 3425 | SP_3425_THIS_ITEM_CANNOT_BE_INCUBATED | This item cannot be incubated. |
| 3426 | SP_3426_PLEASE_SELECT_THE_ITEM_YOUD_LIKE_TO_INCUBATE | Please select the item you'd like to incubate |
| 3427 | SP_3427_YOULL_NEED_AT_LEAST_1_SLOT_OF_EQUIP_INV_3_SLOTS_OF_USE_INV_AND_1_SLOT_OF_ETC_INV | You'll need at least 1 slot of Equip. inv., 3 slots of Use inv., and 1 slot of Etc. inv. available.\r\nWill you incubate the Pigmy Egg? |
| 3428 | SP_3428_UI_UIWINDOWIMG_INCUBATOR_BACKGRND | UI/UIWindow.img/Incubator/backgrnd |
| 3429 | SP_3429_UI_UIWINDOWIMG_INCUBATOR_BTOK | UI/UIWindow.img/Incubator/BtOk |
| 3430 | SP_3430_UI_UIWINDOWIMG_INCUBATOR_BTCANCEL | UI/UIWindow.img/Incubator/BtCancel |
| 3431 | SP_3431_YOUR_INVENTORY_IS_FULL_R_NPLEASE_TRY_AGAIN_LATER | Your inventory is full.\r\nPlease try again later. |
| 3432 | SP_3432_AN_ITEM_IS_FOUND_INSIDE_THE_PIGMY_EGG | An item is found inside the Pigmy Egg. |
| 3433 | SP_3433_YOU_ARE_LACKING_THE_HP_POTION_THAT_YOUR_PET_IS_SUPPOSED_TO_USE | You are lacking the HP Potion that your pet is supposed to use. |
| 3434 | SP_3434_YOU_ARE_LACKING_THE_MP_POTION_THAT_YOUR_PET_IS_SUPPOSED_TO_USE | You are lacking the MP Potion that your pet is supposed to use. |
| 3435 | SP_3435_YOUR_PET_WILL_CONSUME_THE_POTION_ONCE_WHEN_YOUR_HP_GOES_BELOW_HP_ALERT_SETTING | Your pet will consume the Potion once when your HP goes below HP alert setting. |
| 3436 | SP_3436_YOUR_PET_WILL_CONSUME_THE_POTION_ONCE_WHEN_YOUR_MP_GOES_BELOW_MP_ALERT_SETTING | Your pet will consume the Potion once when your MP goes below MP alert setting. |
| 3437 | SP_3437_YOU_MUST_BREAK_THE_SEAL_FIRST | You must break the seal first. |
| 3438 | SP_3438_TO_COMPLETE_THE_QUEST_YOULL_NEED_TO_UNSEAL_THE_QUEST_ITEM | To complete the quest, you'll need to unseal the quest item. |
| 3439 | SP_3439_EFFECT_BASICEFFIMG_ENCHANT_SUCCESS | Effect/BasicEff.img/Enchant/Success |
| 3440 | SP_3440_EFFECT_BASICEFFIMG_ENCHANT_FAILURE | Effect/BasicEff.img/Enchant/Failure |
| 3441 | SP_3441_PURCHASE_SUCCESSFUL_R_ND_YOU_HAVE_RECEIVED_MAPLEPOINTS | Purchase successful.\r\n%d You have received MaplePoints. |
| 3442 | SP_3442_YOU_HAVE_GIVEN_S__R_N_D__S__AS_A_GIFT_R_ND_YOU_HAVE_RECEIVED_MAPLEPOINTS | You have given '%s' \r\n %d [%s] as a gift.\r\n%d You have received MaplePoints. |
| 3443 | SP_3443_ITEM_SPECIAL_MAPLEPOINTIMG_D_ICON | Item/Special/MaplePoint.img/%d/icon |
| 3444 | SP_3444_D_POINT | %d Point |
| 3445 | SP_3445_UNABLE_TO_RETRIEVE_IT_DUE_TO_THE_EQUIPMENT_R_N_CURRENTLY_BEING_WORN_BY_THE_CHARA | Unable to retrieve it due to the equipment\r\n currently being worn by the character. |
| 3446 | SP_3446_YOU_MAY_NOT_POSSESS_MORE_THAN__R_NONE_OF_THIS_ITEM | You may not possess more than \r\none of this item. |
| 3447 | SP_3447_UI_UIWINDOWIMG_BOOK_BTPREV | UI/UIWindow.img/Book/BtPrev |
| 3448 | SP_3448_UI_UIWINDOWIMG_BOOK_BTNEXT | UI/UIWindow.img/Book/BtNext |
| 3449 | SP_3449_UI_UIWINDOWIMG_BOOK_BTCLOSE | UI/UIWindow.img/Book/BtClose |
| 3450 | SP_3450_EMPLOYEE | employee |
| 3451 | SP_3451_HIRED_MERCHANT | Hired Merchant |
| 3452 | SP_3452_ITEM_CASH_0503IMG | Item/Cash/0503.img |
| 3453 | SP_3453_ITEM_CASH_0503IMG_08D | Item/Cash/0503.img/%08d |
| 3454 | SP_3454_ITEM_CASH_0503IMG_08D_EMPLOYEE | Item/Cash/0503.img/%08d/employee |
| 3455 | SP_3455_ITEM_CASH_0503IMG_08D_EFFECT | Item/Cash/0503.img/%08d/effect |
| 3456 | SP_3456_05030002D_SKIN | 050300%02d/skin |
| 3457 | SP_3457_05030002D_EFFECT | 050300%02d/effect |
| 3458 | SP_3458_SIGNBOARD | signboard |
| 3459 | SP_3459_SOLDINFROM | soldInfrom |
| 3460 | SP_3460_SS_HIRED_MERCHANT | %s's Hired Merchant |
| 3461 | SP_3461_STAND_0 | stand/0 |
| 3462 | SP_3462_SS_HIRED_MERCHANT__S | %s's Hired Merchant : %s |
| 3463 | SP_3463_IT_IS_PAST_THE_WORKING_HOURS_R_NSO_THE_STORE_WILL_CLOSE | It is past the working hours,\r\nso the store will close. |
| 3464 | SP_3464_YOUR_SHOP_WAS_CLOSED_AT_THE_DISCRETION_OF_A_GM_PLEASE_SEE_FRED_TO_RECOVER_YOUR_I | Your shop was closed at the discretion of a GM. Please see Fred to recover your items. |
| 3465 | SP_3465_UNABLE_TO_USE_THIS_DUE_TO_THE_REMOTE_SHOP_NOT_BEING_OPEN | Unable to use this due to the Remote Shop not being open. |
| 3466 | SP_3466_IF_THERES_NO_SLOT_AVAILABLE_FOR_THE_ITEM_AT_R_NTHE_INVENTORY_THE_ITEM_CAN_ONLY_B | If there's no slot available for the item at\r\nthe inventory, the item can only be retrieved\r\nthrough Fredrick, the Store Bank NPC.\r\nWill you close the store? |
| 3467 | SP_3467_YOU_CANT_SELL_ITEMS_WHILE_MANAGING_THE_STORE_R_NWOULD_YOU_LIKE_TO_START_MANAGING | You can't sell items while managing the store.\r\nWould you like to start managing the store? |
| 3468 | SP_3468_THE_OWNER_OF_THE_STORE_IS_CURRENTLY_R_NUNDERGOING_STORE_MAINTENANCE_R_NPLEASE_TR | The owner of the store is currently\r\nundergoing store maintenance.\r\nPlease try again in a bit. |
| 3469 | SP_3469_THE_USE_OF_THE_REMOTE_CONTROL_STOPPED_R_NDUE_TO_MOVING_TO_ANOTHER_MAP_PLEASE_USE | The use of the Remote Control stopped\r\ndue to moving to another map. Please use it later. |
| 3470 | SP_3470_STORE_CLOSED___02D__02D | Store Closed / %02d : %02d |
| 3471 | SP_3471_YOUR_STORE_IS_CURRENTLY_OPEN_R_NIN_CHANNEL_S_FREE_MARKET_D_R_NPLEASE_USE_THIS_AF | Your store is currently open\r\nin Channel %s, Free Market %d.\r\nPlease use this after closing the store. |
| 3472 | SP_3472_THE_STORE_IS_OPEN_AT_CHANNEL_S_R_NWOULD_YOU_LIKE_TO_CHANGE_TO_THAT_CHANNEL | The store is open at Channel %s.\r\nWould you like to change to that channel? |
| 3473 | SP_3473_ANOTHER_CHARACTER_IS_CURRENTLY_USING_THE_ITEM_R_NPLEASE_LOG_ON_AS_A_DIFFERENT_CH | Another character is currently using the item.\r\nPlease log on as a different character and close\r\nthe store, or empty out the Store Bank. |
| 3474 | SP_3474_YOU_ARE_CURRENTLY_UNABLE_TO_OPEN_THE_STORE | You are currently unable to open the store. |
| 3475 | SP_3475_YOU_MAY_NOT_OPEN_THE_STORE_NEAR_A_PORTAL | You may not open the store near a portal. |
| 3476 | SP_3476_PLEASE_USE_THIS_AFTER_RETRIEVING_ITEMS_R_N_FROM_FREDRICK_OF_FREE_MARKET | Please use this after retrieving items\r\n from Fredrick of Free Market. |
| 3477 | SP_3477_THE_SOLD_ITEMS_ARE_CROSSED_OFF_THE_LIST_AND_R_NTHE_SALES_FEE_WILL_BE_RETRIEVED | The sold items are crossed off the list, and\r\nthe sales fee will be retrieved. |
| 3478 | SP_3478_BOTH_THE_ITEMS_AND_MESOS_R_NARE_SUCCESSFULLY_RETRIEVED | Both the items and mesos\r\nare successfully retrieved. |
| 3479 | SP_3479_UNABLE_TO_RETRIEVE_THE_ITEMS_AND_MESOS_DUE_TO_THE_STORE_HAVING_TOO_MUCH_MONEY_R_ | Unable to retrieve the items and mesos due to the store having too much money.\r\nPlease go see Fredrick at the Free Market entrance. |
| 3480 | SP_3480_UNABLE_TO_RETRIEVE_THE_ITEMS_BECAUSE_ONE_OF_THE_ITEMS__BUT_THE_MESOS_WERE_RETRIE | Unable to retrieve the items because one of the items ___________, but the Mesos were retrieved.\r\nPlease go see Fredrick at the Free Market entrance. |
| 3481 | SP_3481_UNABLE_TO_RETRIEVE_THE_ITEMS_DUE_TO_FULL_INVENTORY_BUT_THE_MESOS_WERE_RETRIEVED_ | Unable to retrieve the items due to full inventory, but the Mesos were retrieved.\r\nthe item could not be retrieved.\r\nPlease go see Fredrick at the Free Market entrance. |
| 3482 | SP_3482_UNABLE_TO_RETRIEVE_THE_ITEMS_FOR_UNKNOWN_REASONS_BUT_THE_MESOS_WERE_RETRIEVED_R_ | Unable to retrieve the items for unknown reasons, but the Mesos were retrieved.\r\nPlease go see Fredrick at the Free Market entrance. |
| 3483 | SP_3483_YOU_MAY_ONLY_SELL_ONE_OF_THIS_ITEM | You may only sell one of this item. |
| 3484 | SP_3484_YOU_MAY_ONLY_POSSESS_ONE_OF_THIS_ITEM | You may only possess one of this item. |
| 3485 | SP_3485_WILL_YOU_CHANGE_THE_NAME_OF__R_NS | \tWill you change the name of \r\n%s? |
| 3486 | SP_3486_I_LOVE_MAPLESTORY | I LOVE MAPLESTORY! |
| 3487 | SP_3487_I_DONT_THINK_YOU_HAVE_ANY_ITEMS_OR_MONEY_TO_RETRIEVE_HERE_R_NTHIS_IS_WHERE_YOU_R | I don't think you have any items or money to retrieve here.\r\nThis is where you retrieve the items and mesos that you couldn't get from your Hired Merchant. You'll also need to see me as the character that opened the Personal Store. |
| 3488 | SP_3488_YOUR_PERSONAL_STORE_IS_OPEN_BIN_CHANNEL_S_FREE_MARKET_DK_R_NIF_YOU_NEED_ME_THEN_ | Your Personal Store is open #bin Channel %s, Free Market %d#k.\r\nIf you need me, then please close your personal store first before seeing me. |
| 3489 | SP_3489_THIS_IS_CURRENTLY_UNAVAILABLE_R_NPLEASE_TRY_AGAIN_LATER | This is currently unavailable.\r\nPlease try again later |
| 3490 | SP_3490_THESE_WERE_HERE_FOR_D_DAYS_SO_THE_STORING_R_NFEE_WILL_COST_YOU_D_OF_THE_TOTAL_PR | These were here for %d days, so the storing\r\nfee will cost you %d%% of the total price,\r\n%d mesos.\r\nAre you sure you want to retrieve them? |
| 3491 | SP_3491_ARE_YOU_SURE_YOU_WANT_TO_RETRIEVE_THEM | Are you sure you want to retrieve them? |
| 3492 | SP_3492_YOU_HAVE_RETRIEVED_YOUR_ITEMS_AND_MESOS | You have retrieved your items and mesos. |
| 3493 | SP_3493_UNABLE_TO_RETRIEVE_MESOS_AND_ITEMS_DUE_TO_R_NTOO_MUCH_MONEY_STORED_R_NAT_THE_STO | Unable to retrieve mesos and items due to\r\ntoo much money stored\r\nat the Store Bank. |
| 3494 | SP_3494_UNABLE_TO_RETRIEVE_MESOS_AND_ITEMS_DUE_TO_R_NONE_OF_THE_ITEMS_R_NTHAT_CAN_ONLY_B | Unable to retrieve mesos and items due to\r\none of the items\r\nthat can only be possessed one at a time. |
| 3495 | SP_3495_DUE_TO_THE_LACK_OF_SERVICE_FEE_YOU_WERE_UNABLE_TO__R_NRETRIEVE_MESOS_OR_ITEMS | Due to the lack of service fee, you were unable to \r\nretrieve mesos or items. |
| 3496 | SP_3496_UNABLE_TO_RETRIEVE_MESOS_AND_ITEMS_R_NDUE_TO_FULL_INVENTORY | Unable to retrieve mesos and items\r\ndue to full inventory. |
| 3497 | SP_3497_PLEASE_RETRIEVE_YOUR_ITEMS_FROM_FREDRICK | Please retrieve your items from Fredrick. |
| 3498 | SP_3498_HELLO_IM_FREDRICK_THE_UNION_CHIEF_OF_THE_HIRED_MERCHANT_UNION_THE_WAY_TO_USE_HIR | Hello. I'm Fredrick, the Union Chief of the Hired Merchant Union. The way to use Hired Merchants is really not that different from using a regular store, so you need not worry. There are a few things you'll need to be aware of, however, so please read this contract of agreement VERY carefully before hiring the Merchants.\r\n\r\nHired Merchant Contract\r\n1) According to the points stated below, Hired Merchants disappear after storing your items and mesos at the Store Bank.\r\n  - If the Hired Merchant works at the same spot for more than #r24 Hours#k straight, #rthe Hired Merchant's contract will expire, and the Merchant will disappear.#k\r\n  - #rIf the inventory is full#k, the Hired Merchant will be removed from its post (executes after 1st warning)\r\n2) After Hired Merchant disappears, you'll need to see #rFredrick#k, who's at the Free Market Entrance, and retrieve your items and mesos.\r\n3) You will not be able to hire Merchants until you have retrieved all of your mesos and items.\r\n4) A 1% storing fee of the total sales and original price of the item will be charged daily to the user storing the mesos and items at the Store Bank past #r24 hours#k.\r\n5) Once the storing fee exceeds 100%, the mesos and items stored will be donated to the Free Market Fund.\r\n6) GM's hold the right to change the name of the store without notice if it contains vulgar, inappropriate language.\r\n7) Players can have only one Hired Merchant set up at a time. |
| 3499 | SP_3499_D_S_HAVE_BEEN_SOLD_AT_THE_REMOTE_STORE | %d %s have been sold at the Remote Store. |
| 3500 | SP_3500_PETTAMENESSMIN | pettamenessmin |
| 3501 | SP_3501_PETTAMENESSMAX | pettamenessmax |
| 3502 | SP_3502_TAMINGMOBLEVELMIN | tamingmoblevelmin |
| 3503 | SP_3503_TAMINGMOBLEVELMAX | tamingmoblevelmax |
| 3504 | SP_3504_PETTAMENESS | pettameness |
| 3505 | SP_3505_PETSPEED | petspeed |
| 3506 | SP_3506_UNABLE_TO_COMPLETE_THE_QUEST_R_NDUE_TO_A_LACK_OF_PET_R_NPLEASE_TRY_AGAIN_AFTER_S | Unable to complete the quest\r\ndue to a lack of pet.\r\nPlease try again after summoning a pet. |
| 3507 | SP_3507_UNABLE_TO_COMPLETE_THE_QUEST_R_NWITH_THE_CURRENT_PET_YOU_HAVE_SUMMONED_R_NPLEASE | Unable to complete the quest\r\nwith the current pet you have summoned.\r\nPlease try again with a pet\r\nthat meets the quest requirement. |
| 3508 | SP_3508_FUI_UIWINDOWIMG_QUESTICON_9_0_D_R_N | #fUI/UIWindow.img/QuestIcon/9/0# %d\r\n |
| 3509 | SP_3509_YOURE_LACKING_LEVEL_1_OR_2_SKILLS | You're lacking Level 1 or 2 Skills. |
| 3510 | SP_3510_YOURE_LACKING_LEVEL_1_SKILLS | You're lacking Level 1 Skills. |
| 3511 | SP_3511_YOU_ARE_LACKING_IN_EITHER_1ST_2ND_OR_3RD_LEVEL_SKILLS | You are lacking in either 1st, 2nd, or 3rd level skills. |
| 3512 | SP_3512_OO | OO |
| 3513 | SP_3513_INFO | Info |
| 3514 | SP_3514_UI_CASHSHOPIMG_PICTUREPLATE_BTJOIN | UI/CashShop.img/PicturePlate/BtJoin |
| 3515 | SP_3515_UI_CASHSHOPIMG_PICTUREPLATE_BTSHORTCUT | UI/CashShop.img/PicturePlate/BtShortcut |
| 3516 | SP_3516_UI_CASHSHOPIMG_PICTUREPLATE_BTCLOSE | UI/CashShop.img/PicturePlate/BtClose |
| 3517 | SP_3517_UI_CASHSHOPIMG_CSMEMBERSHOP_BTAGREE | UI/CashShop.img/CSMemberShop/BtAgree |
| 3518 | SP_3518_UI_CASHSHOPIMG_CSMEMBERSHOP_BTDISAGREE | UI/CashShop.img/CSMemberShop/BtDisagree |
| 3519 | SP_3519_UI_CASHSHOPIMG_CSMEMBERSHOP_ARTICLE | UI/CashShop.img/CSMemberShop/Article |
| 3520 | SP_3520_UI_CASHSHOPIMG_CSMEMBERSHOP_BTTESTED | UI/CashShop.img/CSMemberShop/BtTested |
| 3521 | SP_3521_UI_CASHSHOPIMG_CSMEMBERSHOP_BTOK | UI/CashShop.img/CSMemberShop/BtOK |
| 3522 | SP_3522_UI_CASHSHOPIMG_CSMEMBERSHOP_BTCANCEL | UI/CashShop.img/CSMemberShop/BtCancel |
| 3523 | SP_3523_UI_CASHSHOPIMG_CSMEMBERSHOP_BACKGRND | UI/CashShop.img/CSMemberShop/backgrnd |
| 3524 | SP_3524_UI_CASHSHOPIMG_PICTUREPLATE_SHORTCUTHELP | UI/CashShop.img/PicturePlate/ShortcutHelp |
| 3525 | SP_3525_SKT | SKT |
| 3526 | SP_3526_KTF | KTF |
| 3527 | SP_3527_LGT | LGT |
| 3528 | SP_3528_PLEASE_RE_CHECK_YOUR_PHONE_NUMBER | Please re-check your phone number. |
| 3529 | SP_3529_PLEASE_RE_CHECK_YOUR_SSN | Please re-check your SSN. |
| 3530 | SP_3530_PLEASE_RE_CHECK_THE_PHONE_COMPANY_YOU_ENTERED | Please re-check the phone company you entered. |
| 3531 | SP_3531_PLEASE_RE_CHECK_YOUR_VERIFICATION_CODE | Please re-check your verification code. |
| 3532 | SP_3532_DISCOUNTRATE | DiscountRate |
| 3533 | SP_3533_MORPH_04D | Morph/%04d |
| 3534 | SP_3534_TAMINGMOB_04D | TamingMob/%04d |
| 3535 | SP_3535_UNAVAILABLE_WHILE_TRANSFORMING | Unavailable while transforming. |
| 3536 | SP_3536_UNAVAILABLE_WHILE_TRANSFORMING_PLEASE_RIGHT_CLICK_THE_ICON_AT_THE_TOP_RIGHT_CORN | Unavailable while transforming. Please right-click the icon at the top right corner to cancel the transformation. |
| 3537 | SP_3537_THIS_SKILL_IS_AVAILABLE_ONLY_WHEN_YOUR_ENERGY_IS_FULLY_CHARGED | This skill is available only when your energy is fully charged. |
| 3538 | SP_3538_THIS_SKILL_IS_AVAILABLE_ONLY_DURING_TRANSFORMATION | This skill is available only during transformation. |
| 3539 | SP_3539_THIS_SKILL_IS_AVAILABLE_ONLY_WHEN_YOU_ARE_RIDING_THE_BATTLESHIP | This skill is available only when you are riding the Battleship. |
| 3540 | SP_3540_YOU_HAVE_EARNED_GUILD_POINTS_D | You have earned Guild Points (+%d) |
| 3541 | SP_3541_YOU_HAVE_LOST_GUILD_POINTS_D | You have lost Guild Points (%d) |
| 3542 | SP_3542_BIGSIZE | bigSize |
| 3543 | SP_3543_YOU_HAVE_PLAYED_MAPLESTORY_FOR_D_HOURS | You have played MapleStory for %d hour(s). |
| 3544 | SP_3544_YOU_HAVE_PLAYED_MAPLESTORY_FOR_D_HOURS_R_NWE_SUGGEST_YOU_TAKE_A_BREAK_FROM_MAPLI | You have played MapleStory for %d hour(s).\r\nWe suggest you take a break from Mapling. |
| 3545 | SP_3545_THERE_ARE_LESS_THAN_6_MEMBERS_REMAINING_SO_THE_QUEST_CANNOT_CONTINUE_YOUR_GUILD_ | There are less than 6 members remaining, so the quest cannot continue. Your Guild Quest will end in 5 seconds. |
| 3546 | SP_3546_THE_USER_THAT_REGISTERED_HAS_DISCONNECTED_SO_THE_QUEST_CANNOT_CONTINUE_YOUR_GUIL | The user that registered has disconnected, so the quest cannot continue. Your Guild Quest will end in 5 seconds. |
| 3547 | SP_3547_PLEASE_GO_SEE_THE_GUILD_QUEST_NPC_AT_CHANNEL_S_IMMEDIATELY_TO_ENTER | Please go see the Guild Quest NPC at Channel %s immediately to enter. |
| 3548 | SP_3548_YOUR_GUILD_IS_UP_NEXT_PLEASE_HEAD_TO_THE_GUILD_QUEST_MAP_AT_CHANNEL_S_AND_WAIT | Your guild is up next. Please head to the Guild Quest map at Channel %s and wait. |
| 3549 | SP_3549_THERES_CURRENTLY_1_GUILD_PARTICIPATING_IN_THE_GUILD_QUEST_AND_YOUR_GUILD_IS_NUMB | There's currently 1 guild participating in the Guild Quest, and your guild is number %d on the waitlist. |
| 3550 | SP_3550_GUILD_QUEST | Guild Quest |
| 3551 | SP_3551_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDRANK_BACKGRND | UI/UIWindow.img/UserList/Guild/GuildRank/backgrnd |
| 3552 | SP_3552_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDRANK_BTLEFT | UI/UIWindow.img/UserList/Guild/GuildRank/BtLeft |
| 3553 | SP_3553_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDRANK_BTRIGHT | UI/UIWindow.img/UserList/Guild/GuildRank/BtRight |
| 3554 | SP_3554_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDRANK_BTOK | UI/UIWindow.img/UserList/Guild/GuildRank/BtOK |
| 3555 | SP_3555_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDRANK_ICON0 | UI/UIWindow.img/UserList/Guild/GuildRank/icon0 |
| 3556 | SP_3556_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDRANK_ICONEFF | UI/UIWindow.img/UserList/Guild/GuildRank/IconEff |
| 3557 | SP_3557_GUILD_QUEST_WAITLIST_NUMBERD | Guild Quest Waitlist number:%d |
| 3558 | SP_3558_YOU_HAVE_ALREADY_APPLIED_FOR_THIS | You have already applied for this. |
| 3559 | SP_3559_____R_N10___R_N33333_____R_N___R_N____R_N_ | 찜 목록 이벤트 응모는\r\n10개의 아이템을 찜하여\r\n33,333원에 가장 근접한 이를 추첨하여\r\n푸짐한 상품을 드립니다.\r\n찜한 아이템 개수가 모자랍니다.\r\n다시 확인하세요. |
| 3560 | SP_3560_33333_____R_N______R_N__R_N___D_ | 33,333원에 가장 근접한 이를 추첨하여\r\n푸짐한 상품을 드립니다. 찜 목록 이벤트에\r\n이대로 응모하시겠습니까?\r\n총 가격 : %d 캐시 |
| 3561 | SP_3561__SMS__ | 휴대전화 SMS로 인증번호가 전송되었습니다. |
| 3562 | SP_3562_____R_N__SMS__R_N____ | 멤버샵 가입을 축하 드립니다.\r\n핸드폰으로 전송된 SMS를 통해\r\n멤버샵을 다운로드 받으실 수 있습니다. |
| 3563 | SP_3563____R_N___ | 서버 연결에 실패하였습니다.\r\n잠시 후에 다시 시도해주세요. |
| 3564 | SP_3564____R_N___ | 전화번호를 잘못 입력하셨습니다.\r\n확인 후에 다시 시도해주세요. |
| 3565 | SP_3565____R_N____R_N___ | 휴대전화 사용자 정보와\r\n입력하신 정보가 일치하지 않습니다.\r\n확인 후에 다시 시도해주세요. |
| 3566 | SP_3566_____R_N___ | 인증 과정중에 오류가 발생하였습니다.\r\n잠시 후에 다시 시도해주세요. |
| 3567 | SP_3567___ | 이미 멤버샵에 가입하셨습니다. |
| 3568 | SP_3568_____R_N___ | 인증 번호를 잘못 입력하셨습니다.\r\n확인 후에 다시 시도해주세요. |
| 3569 | SP_3569_____R_N___ | 요청 처리 시간이 초과되었습니다.\r\n잠시 후에 다시 시도해주세요. |
| 3570 | SP_3570_AN_UNKNOWN_ERROR_HAS_OCCURED | An unknown error has occured. |
| 3571 | SP_3571_PICK_UP_ITEM | Pick up item |
| 3572 | SP_3572_TAKE_HP_POTION | Take HP Potion |
| 3573 | SP_3573_TAKE_MP_POTION | Take MP Potion |
| 3574 | SP_3574_EXTENDING_THE_MOVING_RANGE | Extending the Moving Range |
| 3575 | SP_3575_PICK_UP_ITEMS | Pick Up Items |
| 3576 | SP_3576_PICK_UP_UNCONTESTED_ITEMS | Pick Up Uncontested Items |
| 3577 | SP_3577_RECALL_PET | recall pet |
| 3578 | SP_3578_IGNORE_A_CERTAIN_ITEM | Ignore a certain item |
| 3579 | SP_3579__PLEASE_SELECT_THE_PET_YOUD_LIKE_TO_USE_THE_SCROLL_ON_USING_THE_LEFT_RIGHT_BUTTO |  Please select the pet you'd like to use the scroll on using the left-right button. |
| 3580 | SP_3580_WILL_YOU_EXIT_THIS_CHAT_ROOM | Will you exit this chat room? |
| 3581 | SP_3581_UI_UIWINDOWIMG_TEMPORARYSTATVIEW_D | UI/UIWindow.img/TemporaryStatView/%d |
| 3582 | SP_3582_UI_LOGINIMG_NOTICE_BTRESTART | UI/Login.img/Notice/BtRestart |
| 3583 | SP_3583_UI_LOGINIMG_NOTICE_BTEXIT | UI/Login.img/Notice/BtExit |
| 3584 | SP_3584_UI_LOGINIMG_NOTICE_BTYES1 | UI/Login.img/Notice/BtYes1 |
| 3585 | SP_3585_UI_LOGINIMG_NOTICE_BTNO1 | UI/Login.img/Notice/BtNo1 |
| 3586 | SP_3586_MAPLERESTARTEREXE | MapleRestarter.exe |
| 3587 | SP_3587_EXPIREONLOGOUT | expireOnLogout |
| 3588 | SP_3588_FOLLOW | follow |
| 3589 | SP_3589_ITEM_CASH_0501IMG_08D_EFFECT_ | Item/Cash/0501.img/%08d/effect/ |
| 3590 | SP_3590_LOOSE | loose |
| 3591 | SP_3591_UI_UIWINDOWIMG_SKILL_TIP0 | UI/UIWindow.img/Skill/tip0 |
| 3592 | SP_3592_UI_UIWINDOWIMG_SKILL_TIP1 | UI/UIWindow.img/Skill/tip1 |
| 3593 | SP_3593_UI_UIWINDOWIMG_SKILL_TIP2 | UI/UIWindow.img/Skill/tip2 |
| 3594 | SP_3594_UI_UIWINDOWIMG_SKILL_COOLTIME_D | UI/UIWindow.img/Skill/CoolTime/%d |
| 3595 | SP_3595_FIXDAMAGE | fixdamage |
| 3596 | SP_3596_COOLTIME | cooltime |
| 3597 | SP_3597_YOU_MAY_NOT_USE_THE_SKILL_YET | You may not use the skill yet. |
| 3598 | SP_3598_YOU_MAY_ONLY_USE_THIS_SKILL_WITH_A_ONE_HANDED_WEAPON_EQUIPPED | You may only use this skill with a one-handed weapon equipped. |
| 3599 | SP_3599_THE_ITEM_PICK_UP_SKILL_FOR_S_THE_PET_HAS_BEEN_S | The Item Pick-Up skill for %s the pet has been %s. |
| 3600 | SP_3600_THE_EXPAND_RANGE_OF_MOTION_SKILL_FOR_PET_S_HAS_BEEN_S | The Expand Range of Motion skill for pet %s has been %s. |
| 3601 | SP_3601_THE_AUTO_PICKUP_SKILL_FOR_S_THE_PET_HAS_BEEN_S | The Auto-Pickup Skill for %s the pet has been %s. |
| 3602 | SP_3602_THE_PICK_UP_UNALLOTTED_ITEMS__MESOS_SKILL_FOR_S_THE_PET_HAS_BEEN_S | The Pick up Unallotted Items & Mesos skill for %s the pet has been %s. |
| 3603 | SP_3603_THE_HP_RECHARGE_SKILL_FOR_S_THE_PET_HAS_BEEN_S | The HP Recharge Skill for %s the pet has been %s. |
| 3604 | SP_3604_THE_MP_RECHARGE_SKILL_FOR_S_THE_PET_HAS_BEEN_S | The MP Recharge Skill for %s the pet has been %s. |
| 3605 | SP_3605_S_HAS_S_THE_IGNORE_ITEM_SKILL | %s has %s the Ignore Item skill. |
| 3606 | SP_3606_PETSKILL_UPGRADE_RECALL | petskill upgrade recall |
| 3607 | SP_3607_PETSKILL_UPGRADE_AUTOSPEAKING | petskill upgrade autospeaking |
| 3608 | SP_3608_ADD | Add |
| 3609 | SP_3609_DELETE | Delete |
| 3610 | SP_3610_UI_UIWINDOWIMG_QUEST_BTALERT | UI/UIWindow.img/Quest/BtAlert |
| 3611 | SP_3611_UI_UIWINDOWIMG_QUEST_BTMARKNPC | UI/UIWindow.img/Quest/BtMarkNpc |
| 3612 | SP_3612_UI_UIWINDOWIMG_QUESTALARM_BACKGRNDMAX | UI/UIWindow.img/QuestAlarm/backgrndmax |
| 3613 | SP_3613_UI_UIWINDOWIMG_QUESTALARM_BACKGRNDMIN | UI/UIWindow.img/QuestAlarm/backgrndmin |
| 3614 | SP_3614_UI_UIWINDOWIMG_QUESTALARM_BACKGRNDCENTER | UI/UIWindow.img/QuestAlarm/backgrndcenter |
| 3615 | SP_3615_UI_UIWINDOWIMG_QUESTALARM_BACKGRNDBOTTOM | UI/UIWindow.img/QuestAlarm/backgrndbottom |
| 3616 | SP_3616_MAPLE_ISLAND | Maple Island |
| 3617 | SP_3617_VICTORIA_ISLAND | Victoria Island |
| 3618 | SP_3618_OSSYRIA | Ossyria |
| 3619 | SP_3619_4TH_JOB_ADVANCEMENT | 4th Job Advancement |
| 3620 | SP_3620_QUEST_HELPER_D_5 | Quest Helper (%d/5) |
| 3621 | SP_3621_UI_BASICIMG_BTCLOSE2 | UI/Basic.img/BtClose2 |
| 3622 | SP_3622_UI_UIWINDOWIMG_ITEMSEARCH_CATEGORYBACK | UI/UIWindow.img/itemSearch/categoryback |
| 3623 | SP_3623_UI_UIWINDOWIMG_ITEMSEARCH_BACKGRND | UI/UIWindow.img/itemSearch/backgrnd |
| 3624 | SP_3624_UI_UIWINDOWIMG_ITEMSEARCH_SEARCHBACK | UI/UIWindow.img/itemSearch/searchback |
| 3625 | SP_3625_UI_UIWINDOWIMG_ITEMSEARCH_RESULTBACK | UI/UIWindow.img/itemSearch/resultback |
| 3626 | SP_3626_UI_UIWINDOWIMG_ITEMSEARCH_BTRIGHT_NORMAL_0 | UI/UIWindow.img/itemSearch/BtRight/normal/0 |
| 3627 | SP_3627_UI_UIWINDOWIMG_ITEMSEARCH_BACKGRND2 | UI/UIWindow.img/itemSearch/backgrnd2 |
| 3628 | SP_3628__S__R_NWOULD_YOU_LIKE_TO_REGISTER | [%s]\r\nWould you like to register? |
| 3629 | SP_3629_WOULD_YOU_LIKE_TO_SEARCH_FOR_R_N_S__R_NTHE_OWL_OF_MINERVA_WILL_DISAPPEAR_R_NS_R_ | Would you like to search for\r\n[%s]?\r\nThe Owl of Minerva will disappear\r\n%s\r\nif there's a search result after pressing OK. |
| 3630 | SP_3630_SHOW_200_RESULTS_IN_ASCENDING_ORDER | Show 200 results in ascending order |
| 3631 | SP_3631_SHOW_200_RESULTS_IN_DESCENDING_ORDER | Show 200 results in descending order |
| 3632 | SP_3632_SEARCH_RESULTS_FOR | Search results for |
| 3633 | SP_3633_THAT_YOU_ENTERED | that you entered. |
| 3634 | SP_3634_TOTAL_OF | Total of |
| 3635 | SP_3635_RESULTS_AVAILABLE | results available. |
| 3636 | SP_3636_YOU_MAY_NOT_USE_GO_IF_THE_STORE_IS_EITHER_ON_A_DIFFERENT_CHANNEL_OR_CLOSED | You may not use "GO" if the store is either on a different channel or closed. |
| 3637 | SP_3637_UNABLE_TO_FIND_THE_ITEM_R_NYOU_HAVE_ENTERED | Unable to find the item\r\nyou have entered. |
| 3638 | SP_3638_STORE_CLOSED | Store Closed |
| 3639 | SP_3639_CANT_GO | Can't Go |
| 3640 | SP_3640_THIS_STORE_IS_OPEN_AT_ROOM_D_ON_CHANNEL_S | This store is open at Room %d on Channel %s. |
| 3641 | SP_3641_THIS_CAN_ONLY_BE_USED_INSIDE_THE_FREE_MARKET | This can only be used inside the Free Market. |
| 3642 | SP_3642_NOT_AVAILABLE_IN_THE_FREE_MARKET | Not available in the Free Market. |
| 3643 | SP_3643_UI_UIWINDOWIMG_ITEMSEARCH_BTGO | UI/UIWindow.img/itemSearch/BtGo |
| 3644 | SP_3644_UI_UIWINDOWIMG_ITEMSEARCH_BTSEARCH | UI/UIWindow.img/itemSearch/BtSearch |
| 3645 | SP_3645_UI_UIWINDOWIMG_ITEMSEARCH_BTOK | UI/UIWindow.img/itemSearch/BtOK |
| 3646 | SP_3646_UI_UIWINDOWIMG_ITEMSEARCH_BTLINK | UI/UIWindow.img/itemSearch/BtLink |
| 3647 | SP_3647_UI_UIWINDOWIMG_ITEMSEARCH_BTINFO | UI/UIWindow.img/itemSearch/BtInfo |
| 3648 | SP_3648_UI_UIWINDOWIMG_ITEMSEARCH_BTREGIST | UI/UIWindow.img/itemSearch/BtRegist |
| 3649 | SP_3649_UI_UIWINDOWIMG_ITEMSEARCH_BTSEARCH2 | UI/UIWindow.img/itemSearch/BtSearch2 |
| 3650 | SP_3650__ITEM_PER_BUNDLE | # Item Per Bundle |
| 3651 | SP_3651_PRICE_PER_BUNDLE | Price Per Bundle |
| 3652 | SP_3652_TOTAL__SOLD | Total # Sold |
| 3653 | SP_3653_UI_UIWINDOWIMG_ITEMSEARCH_ICON0 | UI/UIWindow.img/itemSearch/icon0 |
| 3654 | SP_3654_EFFECT_MAPEFFIMG_VIEWRANGE_1 | Effect/MapEff.img/Viewrange/1 |
| 3655 | SP_3655_EFFECT_MAPEFFIMG_VIEWRANGE_3 | Effect/MapEff.img/Viewrange/3 |
| 3656 | SP_3656_PICKUPBLOCK | pickUpBlock |
| 3657 | SP_3657_UI_UIWINDOWIMG_RPSGAME_BACKGRND | UI/UIWindow.img/RpsGame/backgrnd |
| 3658 | SP_3658_UI_UIWINDOWIMG_RPSGAME_BTSTART | UI/UIWindow.img/RpsGame/BtStart |
| 3659 | SP_3659_UI_UIWINDOWIMG_RPSGAME_BTCONTINUE | UI/UIWindow.img/RpsGame/BtContinue |
| 3660 | SP_3660_UI_UIWINDOWIMG_RPSGAME_BTRETRY | UI/UIWindow.img/RpsGame/BtRetry |
| 3661 | SP_3661_UI_UIWINDOWIMG_RPSGAME_BTEXIT | UI/UIWindow.img/RpsGame/BtExit |
| 3662 | SP_3662_UI_UIWINDOWIMG_RPSGAME_BTROCK | UI/UIWindow.img/RpsGame/BtRock |
| 3663 | SP_3663_UI_UIWINDOWIMG_RPSGAME_BTPAPER | UI/UIWindow.img/RpsGame/BtPaper |
| 3664 | SP_3664_UI_UIWINDOWIMG_RPSGAME_BTSCISSOR | UI/UIWindow.img/RpsGame/BtScissor |
| 3665 | SP_3665_UI_UIWINDOWIMG_RPSGAME_DRAW | UI/UIWindow.img/RpsGame/draw |
| 3666 | SP_3666_UI_UIWINDOWIMG_RPSGAME_WIN | UI/UIWindow.img/RpsGame/win |
| 3667 | SP_3667_UI_UIWINDOWIMG_RPSGAME_LOSE | UI/UIWindow.img/RpsGame/lose |
| 3668 | SP_3668_UI_UIWINDOWIMG_RPSGAME_TIMEOVER | UI/UIWindow.img/RpsGame/timeover |
| 3669 | SP_3669_UI_UIWINDOWIMG_RPSGAME_FROCK | UI/UIWindow.img/RpsGame/Frock |
| 3670 | SP_3670_UI_UIWINDOWIMG_RPSGAME_FPAPER | UI/UIWindow.img/RpsGame/Fpaper |
| 3671 | SP_3671_UI_UIWINDOWIMG_RPSGAME_FSCISSOR | UI/UIWindow.img/RpsGame/Fscissor |
| 3672 | SP_3672_UI_UIWINDOWIMG_RPSGAME_ROCK | UI/UIWindow.img/RpsGame/rock |
| 3673 | SP_3673_UI_UIWINDOWIMG_RPSGAME_PAPER | UI/UIWindow.img/RpsGame/paper |
| 3674 | SP_3674_UI_UIWINDOWIMG_RPSGAME_SCISSOR | UI/UIWindow.img/RpsGame/scissor |
| 3675 | SP_3675_EBROCK_PAPER_SCISSORS_NOTICENK_R_NTHE_PARTICIPATION_FEE_FOR_THIS_ROCK_PAPER_SCIS | #e#bRock, Paper, Scissors Notice#n#k\r\nThe participation fee for this Rock, Paper, Scissors game is #r1,000 mesos#k.\r\nWinning games will allow you to obtain various Winning Streak Certificates awarded for each consecutive win, but failure to complete the challenge will result in not receiving the Cerficate.\r\nThe received Certificate can be traded with the NPC's Paul, Jean, Martin, and Tony. |
| 3676 | SP_3676_PRESS_START_TO_START_THE_GAME | Press 'Start' to start the game. |
| 3677 | SP_3677_PLEASE_SELECT_ROCK_PAPER_OR_SCISSORS_IN_D_SECONDS | Please select Rock, Paper, or Scissors in %d seconds. |
| 3678 | SP_3678_TO_CHALLENGE_THE_D_GAME_WINNING_STREAK_CHALLENGE_PRESS_CONTINUE_A_FAILED_CHALLEN | To challenge the %d-game Winning Streak Challenge, press 'Continue'. A failed challenge will result in no reward. |
| 3679 | SP_3679_CONGRATULATIONS_ON_CONQUERING_THE_10_GAME_WINNING_STREAK_CHALLENGE | Congratulations on conquering the 10-game Winning Streak Challenge! |
| 3680 | SP_3680_YOU_HAVE_LOST_THE_GAME_DUE_TO_BEING_OVER_THE_TIME_LIMIT_PRESS_RESTART_TO_TRY_AGA | You have lost the game due to being over the time limit. Press 'Restart' to try again, which will cost you the participation fee of 1,000 mesos. |
| 3681 | SP_3681_HERES_500_MESOS_AS_A_CONSOLATION_PRIZE_PRESS_RESTART_TO_TRY_AGAIN_WHICH_WILL_COS | Here's 500 mesos as a consolation prize. Press 'Restart' to try again, which will cost you the participation fee of 1,000 mesos. |
| 3682 | SP_3682_PRESS_RESTART_TO_TRY_AGAIN_WHICH_WILL_COST_YOU_THE_PARTICIPATION_FEE_OF_1000_MES | Press 'Restart' to try again, which will cost you the participation fee of 1,000 mesos. |
| 3683 | SP_3683_YOU_MAY_ONLY_PARTICIPATE_IN_THIS_GAME_WITH_AT_LEAST_ONE_FREE_SLOT_AVAILABLE_IN_T | You may only participate in this game with at least one free slot available in the etc. inventory. |
| 3684 | SP_3684_YOU_DO_NOT_HAVE_ENOUGH_MESOS_TO_PAY_FOR_THE_PARTICIPATION_FEE_OF_1000_MESOS | You do not have enough mesos to pay for the participation fee of 1,000 mesos. |
| 3685 | SP_3685_YOU_MAY_ONLY_PLAY_THIS_D_TIMES_A_DAY | You may only play this %d times a day. |
| 3686 | SP_3686_KSUMMER_EVENT_RB100_PM__700_PM_RREXP_XD_DROP_RATE_XD | #kSummer Event\r#b1:00 pm ~ 7:00 pm\r#rEXP x%d, Drop Rate x%d |
| 3687 | SP_3687_CATCH_SUCCESS | Catch/Success |
| 3688 | SP_3688_CATCH_FAIL | Catch/Fail |
| 3689 | SP_3689_EFFECT_BASICEFFIMG_ASSASSINATION_EFFECT | Effect/BasicEff.img/Assassination/effect |
| 3690 | SP_3690_CRITICALDAMAGE | criticalDamage |
| 3691 | SP_3691_SKILL_03DIMG_SKILL_07D_KEYDOWN | Skill/%03d.img/skill/%07d/keydown |
| 3692 | SP_3692_KEYDOWN | keydown |
| 3693 | SP_3693_FROM_2D_2D_4D___MM_DD_YYYY | From %2d/%2d/%4d   (mm/dd/yyyy) |
| 3694 | SP_3694_TO_2D_2D_4D_____MM_DD_YYYY | to %2d/%2d/%4d     (mm/dd/yyyy) |
| 3695 | SP_3695__MON_ | [Mon] |
| 3696 | SP_3696__TUES_ | [Tues] |
| 3697 | SP_3697__WED_ | [Wed] |
| 3698 | SP_3698__THUR_ | [Thur] |
| 3699 | SP_3699__FRI_ | [Fri] |
| 3700 | SP_3700__SAT_ | [Sat] |
| 3701 | SP_3701__SUN_ | [Sun] |
| 3702 | SP_3702__ONLY |  only |
| 3703 | SP_3703_FROM_2D00_AM_TO | from %2d:00 am to |
| 3704 | SP_3704_FROM_2D00_PM_TO | from %2d:00 pm to |
| 3705 | SP_3705_2D_AM | %2d am |
| 3706 | SP_3706_2D_PM | %2d pm |
| 3707 | SP_3707_THIS_ITEM_HAS_LIMITED_AVAILABILITY | This item has limited availability. |
| 3708 | SP_3708_THERE_ARE_CURRENTLY_D_LEFT | There are currently %d left. |
| 3709 | SP_3709_THIS_ITEM_WILL_BE_ON_SALE_ONLY_FOR_THIS_LIMITED_TIME | This item will be on sale only for this limited time. |
| 3710 | SP_3710_HURRY | Hurry!!! |
| 3711 | SP_3711___LIMITED_SALE_GUIDE__ | [ Limited Sale Guide ] |
| 3712 | SP_3712_LOOP | Loop |
| 3713 | SP_3713_INVISIBLE | invisible |
| 3714 | SP_3714_STRING_TOOLTIPHELPIMG_MAPOBJECT_D | String/ToolTipHelp.img/Mapobject/%d |
| 3715 | SP_3715_TOOLTIP | ToolTip |
| 3716 | SP_3716_EFFECT_ITEMEFFIMG_1112001_0 | Effect/ItemEff.img/1112001/0 |
| 3717 | SP_3717_EFFECT_ITEMEFFIMG_1112001_1 | Effect/ItemEff.img/1112001/1 |
| 3718 | SP_3718_SIDMARRIAGEDIVORCEERRORNOMONEY | SID_MARRIAGE_DIVORCE_ERROR_NOMONEY |
| 3719 | SP_3719_UI_UIWINDOWIMG_WEDDING_INVITATION_VEGAS | UI/UIWindow.img/Wedding/Invitation/Vegas |
| 3720 | SP_3720_UI_UIWINDOWIMG_WEDDING_INVITATION_CATHEDRAL | UI/UIWindow.img/Wedding/Invitation/Cathedral |
| 3721 | SP_3721_PLEASE_ENTER_THE_TEXT | Please enter the text. |
| 3722 | SP_3722_ARE_YOU_SURE_YOU_WANT_TO_DELETE_THE_POST | Are you sure you want to delete the post? |
| 3723 | SP_3723_PLEASE_DELETE_THE_CURRENT_NOTICE_R_NAND_THEN_TRY_AGAIN | Please delete the current notice\r\nand then try again. |
| 3724 | SP_3724_UI_GUILDBBSIMG_GUILDBBS_BTQUIT | UI/GuildBBS.img/GuildBBS/BtQuit |
| 3725 | SP_3725_UI_GUILDBBSIMG_GUILDBBS_EMOTICON_BASIC_0 | UI/GuildBBS.img/GuildBBS/Emoticon/Basic/0 |
| 3726 | SP_3726_UI_GUILDBBSIMG_GUILDBBS_EMOTICON_BASIC_1 | UI/GuildBBS.img/GuildBBS/Emoticon/Basic/1 |
| 3727 | SP_3727_UI_GUILDBBSIMG_GUILDBBS_EMOTICON_BASIC_2 | UI/GuildBBS.img/GuildBBS/Emoticon/Basic/2 |
| 3728 | SP_3728_UI_GUILDBBSIMG_GUILDBBS_EMOTICON_SELECT | UI/GuildBBS.img/GuildBBS/Emoticon/Select |
| 3729 | SP_3729_UI_GUILDBBSIMG_GUILDBBS_BTNOTICE | UI/GuildBBS.img/GuildBBS/BtNotice |
| 3730 | SP_3730_UI_GUILDBBSIMG_GUILDBBS_BTWRITE | UI/GuildBBS.img/GuildBBS/BtWrite |
| 3731 | SP_3731_UI_GUILDBBSIMG_GUILDBBS_BTRETOUCH | UI/GuildBBS.img/GuildBBS/BtRetouch |
| 3732 | SP_3732_UI_GUILDBBSIMG_GUILDBBS_BTDELETE | UI/GuildBBS.img/GuildBBS/BtDelete |
| 3733 | SP_3733_UI_GUILDBBSIMG_GUILDBBS_BTREPLY | UI/GuildBBS.img/GuildBBS/BtReply |
| 3734 | SP_3734_UI_GUILDBBSIMG_GUILDBBS_BTREPLYDELETE | UI/GuildBBS.img/GuildBBS/BtReplyDelete |
| 3735 | SP_3735_UI_GUILDBBSIMG_GUILDBBS_BACKGRND2 | UI/GuildBBS.img/GuildBBS/backgrnd2 |
| 3736 | SP_3736_UI_GUILDBBSIMG_GUILDBBS_BTREGISTER | UI/GuildBBS.img/GuildBBS/BtRegister |
| 3737 | SP_3737_UI_GUILDBBSIMG_GUILDBBS_BTCANCEL | UI/GuildBBS.img/GuildBBS/BtCancel |
| 3738 | SP_3738_UI_GUILDBBSIMG_GUILDBBS_MOVEEMOTICON_BTLEFT | UI/GuildBBS.img/GuildBBS/MoveEmoticon/BtLeft |
| 3739 | SP_3739_UI_GUILDBBSIMG_GUILDBBS_MOVEEMOTICON_BTRIGHT | UI/GuildBBS.img/GuildBBS/MoveEmoticon/BtRight |
| 3740 | SP_3740_UI_GUILDBBSIMG_GUILDBBS_BACKGRND3 | UI/GuildBBS.img/GuildBBS/backgrnd3 |
| 3741 | SP_3741_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDINFO_BTGUILDBBS | UI/UIWindow.img/UserList/Guild/GuildInfo/BtGuildBBS |
| 3742 | SP_3742_UI_GUILDBBSIMG_GUILDBBS_BACKGRND | UI/GuildBBS.img/GuildBBS/backgrnd |
| 3743 | SP_3743_THIS_MESSAGE_HAD_BEEN_DELETED_BY_ANOTHER_USER_R_NPLEASE_CHECK_AGAIN | This message had been deleted by another user.\r\nPlease check again. |
| 3744 | SP_3744_UI_GUILDBBSIMG_GUILDBBS_EMOTICON_CASH_D | UI/GuildBBS.img/GuildBBS/Emoticon/Cash/%d |
| 3745 | SP_3745_THIS_MEGAPHONE_IS_ONLY_AVAILABLE_FOR__R_NCHARACTERS_THAT_ARE_OVER_LEVEL_10 | This megaphone is only available for \r\ncharacters that are over Level 10. |
| 3746 | SP_3746_HIRED_MERCHANTS_SHOP_IS_ONLY_AVAILABLE_FOR__R_NCHARACTERS_THAT_ARE_OVER_LEVEL_15 | Hired Merchant's Shop is only available for \r\ncharacters that are over Level 15. |
| 3747 | SP_3747_GACHAPON_IS_ONLY_AVAILABLE_FOR__R_NCHARACTERS_THAT_ARE_OVER_LEVEL_15 | Gachapon is only available for \r\ncharacters that are over Level 15. |
| 3748 | SP_3748_THIS_TELEPORT_ROCK_IS_ONLY_AVAILABLE_FOR__R_NCHARACTERS_THAT_ARE_OVER_LEVEL_6 | This teleport rock is only available for \r\ncharacters that are over level 6. |
| 3749 | SP_3749_THIS_ITEM_CAN_ONLY_BE_USED_15_DAYS__R_NAFTER_THE_ACCOUNTS_REGISTRATION | This item can only be used 15 days \r\nafter the account's registration. |
| 3750 | SP_3750_EFFECT_ITEMEFFIMG_1112800_0 | Effect/ItemEff.img/1112800/0 |
| 3751 | SP_3751_EFFECT_ITEMEFFIMG_1112800_1 | Effect/ItemEff.img/1112800/1 |
| 3752 | SP_3752_EFFECT_ITEMEFFIMG_1112801_0 | Effect/ItemEff.img/1112801/0 |
| 3753 | SP_3753_EFFECT_ITEMEFFIMG_1112801_1 | Effect/ItemEff.img/1112801/1 |
| 3754 | SP_3754_EFFECT_ITEMEFFIMG_1112802_0 | Effect/ItemEff.img/1112802/0 |
| 3755 | SP_3755_EFFECT_ITEMEFFIMG_1112802_1 | Effect/ItemEff.img/1112802/1 |
| 3756 | SP_3756_ | 억 |
| 3757 | SP_3757_ | 만 |
| 3758 | SP_3758__D |  %d |
| 3759 | SP_3759__D |  %d십 |
| 3760 | SP_3760__D |  %d백 |
| 3761 | SP_3761__D |  %d천 |
| 3762 | SP_3762__D |  %d천만 |
| 3763 | SP_3763__ |  ... |
| 3764 | SP_3764_0000 | 0000 |
| 3765 | SP_3765_BONUS_EXP_FOR_PARTY_D | Bonus EXP for PARTY (+%d) |
| 3766 | SP_3766_BONUS_EVENT_PARTY_EXP_D_XD | Bonus Event Party EXP (+%d) x%d |
| 3767 | SP_3767_BONUS_EXP_FOR_HUNTING_OVER_D_HRS_D | Bonus EXP for hunting over %d hrs. (+%d) |
| 3768 | SP_3768_A_BONUS_EXP_D_IS_AWARDED_FOR_EVERY_3RD_MONSTER_DEFEATED | A bonus EXP %d%% is awarded for every 3rd monster defeated. |
| 3769 | SP_3769_SCRIPT | script |
| 3770 | SP_3770_HIDETOOLTIP | hideTooltip |
| 3771 | SP_3771_STRING_TOOLTIPHELPIMG_PORTALTOOLTIP_D | String/ToolTipHelp.img/PortalTooltip/%d |
| 3772 | SP_3772_SLANG | slang |
| 3773 | SP_3773_DAMAGEDBYMOB | damagedByMob |
| 3774 | SP_3774_DROPITEMPERIOD | dropItemPeriod |
| 3775 | SP_3775_DUE_TO_YOUR_FAILURE_TO_PROTECT_THE_MOON_BUNNY_YOU_HAVE_BEEN_TRANSPORTED_TO_THE_E | Due to your failure to protect the Moon Bunny, you have been transported to the Exile Map. |
| 3776 | SP_3776_YOU_HAVE_FAILED_TO_PROTECT_TYLUS | \tYou have failed to protect Tylus. |
| 3777 | SP_3777_THE_POOR_WILD_BOAR_HAS_DIED | The poor wild boar has died. |
| 3778 | SP_3778_YOU_HAVE_BEEN_SEDUCED_WHICH_DISABLES_YOU_FROM_MOVING | You have been seduced, which disables you from moving. |
| 3779 | SP_3779_YOU_WILL_BECOME_AN_UNDEAD_AND_THE_EFFECTS_OF_YOUR_RECOVERY_WILL_DIMINISH_BY_50 | You will become an Undead and the effects of your Recovery will diminish by 50%. |
| 3780 | SP_3780_ATTACHFACING | attachfacing |
| 3781 | SP_3781_DIRECTION | direction |
| 3782 | SP_3782_BACKTILE | backTile |
| 3783 | SP_3783_ACTIVATESKILLID | activateSkillID |
| 3784 | SP_3784_ACTIVATEBYTOUCH | activateByTouch |
| 3785 | SP_3785_TO_THE_CREW_THAT_HAVE_FINALLY_CONQUERED_HORNED_TAIL_AFTER_NUMEROUS_ATTEMPTS_I_SA | To the crew that have finally conquered Horned Tail after numerous attempts, I salute thee! You are the true heroes of Leafre!! |
| 3786 | SP_3786_OH_THE_EXPLORATION_TEAM_WHO_HAS_DEFEATED_PINK_BEAN_WITH_UNDYING_FERVOR_YOU_ARE_T | Oh, the exploration team who has defeated Pink Bean with undying fervor! You are the true victors of time! |
| 3787 | SP_3787_MAP_OBJ_ETCIMG_TIMER_BACKGRNDHOUR | Map/Obj/etc.img/timer/backgrndhour |
| 3788 | SP_3788_SHOWMESSAGE | showMessage |
| 3789 | SP_3789_THE_MOON_BUNNY_MADE_RICE_CAKE_NUMBER_D | The Moon Bunny made rice cake number %d. |
| 3790 | SP_3790_THE_MOON_BUNNY_IS_FEELING_SICK_PLEASE_PROTECT_IT_SO_IT_CAN_MAKE_DELICIOUS_RICE_C | The Moon Bunny is feeling sick. Please protect it so it can make delicious rice cakes. |
| 3791 | SP_3791_TYLUS_IS_UNDER_DANGER_PLEASE_PROTECT_HIM | Tylus is under danger. Please protect him. |
| 3792 | SP_3792_THE_WATCH_HOG_IS_IN_DANGER_PLEASE_PROTECT_IT | The Watch Hog is in danger. Please protect it! |
| 3793 | SP_3793_YOUR_PET_CANNOT_CONSUME_THIS_FOOD_R_NPLEASE_CHECK_AGAIN | Your pet cannot consume this food.\r\nPlease check again. |
| 3794 | SP_3794_KEYDOWNEND | keydownend |
| 3795 | SP_3795_SKILL_03DIMG_SKILL_07D_KEYDOWNEND | Skill/%03d.img/skill/%07d/keydownend |
| 3796 | SP_3796_DELTA | delta |
| 3797 | SP_3797_ARE_YOU_SURE_YOU_WANT_TO_USE_S | Are you sure you want to use %s? |
| 3798 | SP_3798_YOU_HAVE_USED_S | You have used %s. |
| 3799 | SP_3799_THESE_CONTACT_LENSES_CANNOT_BE_EQUIPPED_BECAUSE_R_NYOU_ARE_CURRENTLY_ADORNING_A_ | These contact lenses cannot be equipped, because\r\nyou are currently adorning a lens of the same color. |
| 3800 | SP_3800_THIS_ITEM_CAN_ONLY_BE_USED_WITH_A_PET | This item can only be used with a pet. |
| 3801 | SP_3801_RANDOM | random |
| 3802 | SP_3802_CHARACTER_TAMINGMOB | Character/TamingMob |
| 3803 | SP_3803_CHARACTER_TAMINGMOB_ | Character/TamingMob/ |
| 3804 | SP_3804_CHARACTERSTART | characterStart |
| 3805 | SP_3805_CHARACTEREND | characterEnd |
| 3806 | SP_3806_TAMED_MONSTER | TAMED MONSTER |
| 3807 | SP_3807_SADDLE | SADDLE |
| 3808 | SP_3808_UI_UIWINDOWIMG_NEQUIP_BACKGRND1 | UI/UIWindow.img/NEquip/backgrnd1 |
| 3809 | SP_3809_UI_UIWINDOWIMG_NEQUIP_BACKGRND | UI/UIWindow.img/NEquip/backgrnd |
| 3810 | SP_3810_MONSTER_EQUIP | MONSTER EQUIP. |
| 3811 | SP_3811_PET_EQUIP | PET EQUIP. |
| 3812 | SP_3812_PET_RING | PET RING |
| 3813 | SP_3813_THIS_SKILL_REQUIRES_A_TAMED_MONSTER_AND_A_SADDLE | This skill requires a tamed monster and a saddle. |
| 3814 | SP_3814_NOT_AVAILABLE_DURING_THE_RIDE | Not available during the ride. |
| 3815 | SP_3815_NOT_AVAILABLE_WHILE_RIDING_ON_THE_TAMED_MONSTER | Not available while riding on the tamed monster. |
| 3816 | SP_3816_TAMINGMOB | tamingMob |
| 3817 | SP_3817_YOU_CANNOT_RIDE_ON_THIS_TAMED_MONSTER | You cannot ride on this tamed monster. |
| 3818 | SP_3818_YOU_CAN_RIDE_THE_TAMED_CASH_MONSTER_WHEN_IT_IS_EQUIPPED_WITH_A_CASH_SADDLE | You can ride the tamed cash monster when it is equipped with a Cash Saddle. |
| 3819 | SP_3819_INCFATIGUE | incFatigue |
| 3820 | SP_3820_LEFT | left |
| 3821 | SP_3821_RIGHT | right |
| 3822 | SP_3822_TOP | top |
| 3823 | SP_3823_BOTTOM | bottom |
| 3824 | SP_3824_CREATE | create |
| 3825 | SP_3825_YOU_DO_NOT_HAVE_ENOUGH_SLOTS_IN_YOUR_ITEM_INVENTORY | \tYou do not have enough slots in your item inventory. |
| 3826 | SP_3826_YOU_DO_NOT_HAVE_ENOUGH_ETC_INVENTORY_SLOTS | You do not have enough etc. inventory slots. |
| 3827 | SP_3827_YOU_HAVE_SUCCESSFULLY_TAMED_A_MONSTER | You have successfully tamed a monster. |
| 3828 | SP_3828_UNABLE_TO_FIND_A_TAMABLE_MONSTER_IN_THIS_AREA | Unable to find a tamable monster in this area. |
| 3829 | SP_3829_ITEM_CONSUME_0227IMG | Item/Consume/0227.img |
| 3830 | SP_3830_ITEM_CONSUME_0238IMG | Item/Consume/0238.img |
| 3831 | SP_3831_SWORDOFHERO1 | swordOfHero1 |
| 3832 | SP_3832_SWORDOFHERO2 | swordOfHero2 |
| 3833 | SP_3833_SANCTUARY | sanctuary |
| 3834 | SP_3834_VOLCANO | volcano |
| 3835 | SP_3835_MEDUSA | medusa |
| 3836 | SP_3836_ICEFIELD | icefield |
| 3837 | SP_3837_JUDGEMENT | judgement |
| 3838 | SP_3838_TORNADO | tornado |
| 3839 | SP_3839_SPIRIT | spirit |
| 3840 | SP_3840_ISOLATION | isolation |
| 3841 | SP_3841_CHALLENGE | challenge |
| 3842 | SP_3842_RESURRECTION | resurrection |
| 3843 | SP_3843_SPARK | spark |
| 3844 | SP_3844_SMOKESHELL | smokeshell |
| 3845 | SP_3845_MASTERY_BOOK | Mastery Book |
| 3846 | SP_3846_SKILL_BOOK | Skill Book |
| 3847 | SP_3847_YOU_CANNOT_USE_S | You cannot use %s. |
| 3848 | SP_3848_DESPITE_USING_S_THE_EFFECT_WAS_NOWHERE_TO_BE_FOUND | Despite using %s, the effect was nowhere to be found. |
| 3849 | SP_3849_THE_BOOK_OF_MASTERY_GLOWS_BRIGHTLY_AND_THE_CURRENT_SKILLS_HAVE_GONE_THROUGH_AN_U | The Book of Mastery glows brightly, and the current skills have gone through an upgrade. |
| 3850 | SP_3850_THE_SKILL_BOOK_GLOWS_BRIGHTLY_AND_NEW_SKILLS_HAVE_NOW_BEEN_ADDED | The Skill Book glows brightly, and new skills have now been added. |
| 3851 | SP_3851_REQSKILLLEVEL | reqSkillLevel |
| 3852 | SP_3852__MASTER_LEVEL__D_ | [Master Level : %d] |
| 3853 | SP_3853_SS_TIME_HAS_RUN_OUT_AND_WILL_DISAPPEAR | %s's time has run out, and will disappear. |
| 3854 | SP_3854_S_IS_DISAPPEARING | %s is disappearing. |
| 3855 | SP_3855_UNKNOWN | \tUnknown |
| 3856 | SP_3856_USE3 | Use3 |
| 3857 | SP_3857_USE4 | Use4 |
| 3858 | SP_3858_USE5 | Use5 |
| 3859 | SP_3859_METEOR | meteor |
| 3860 | SP_3860_CHAINLIGHTNING | chainlightning |
| 3861 | SP_3861_BLIZZARD | blizzard |
| 3862 | SP_3862_HOLYSHIELD | holyshield |
| 3863 | SP_3863_SHOWDOWN | showdown |
| 3864 | SP_3864_NINJASTORM | ninjastorm |
| 3865 | SP_3865_BRANDISH1 | brandish1 |
| 3866 | SP_3866_BRANDISH2 | brandish2 |
| 3867 | SP_3867_BLAST | blast |
| 3868 | SP_3868_PARALYZE | paralyze |
| 3869 | SP_3869_GENESIS | genesis |
| 3870 | SP_3870_YOU_CANNOT_RECEIVE_IT_IN_THE_CURRENT_MAP_R_NPLEASE_RECEIVE_IT_FROM_R_NDUEY_THE_P | You cannot receive it in the current map.\r\nPlease receive it from\r\nDuey the package deliverer. |
| 3871 | SP_3871_THIS_ITEM_CANNOT_BE_SENT | \t\tThis item cannot be sent. |
| 3872 | SP_3872_THE_RECEIVED_PACKAGE_CAN_BE_OBTAINED_THROUGH_THE_QUICK_DELIVERY_NPC | The received package can be obtained through the Quick Delivery NPC. |
| 3873 | SP_3873_QUICK_DELIVEY_PACKAGE | \tQuick delivey/Package |
| 3874 | SP_3874_ARRIVED | \tArrived! |
| 3875 | SP_3875_PACKAGE_R_NRECEIVED | Package\r\nReceived. |
| 3876 | SP_3876_THE_QUICK_DELIVERY_HAS_ARRIVED | The Quick Delivery has arrived! |
| 3877 | SP_3877__S_ | [%s] |
| 3878 | SP_3878_THE_PACKAGE_SENT_BY__S__R_NHAS_BEEN_EXPIRED_R_N | The package sent by [%s]\r\nhas been expired.\r\n |
| 3879 | SP_3879__R_NMESO__D | \r\nmeso : %d |
| 3880 | SP_3880__R_NITEM__S | \r\nitem : %s |
| 3881 | SP_3881_DELIVERING | Delivering |
| 3882 | SP_3882_D_DAYS | %d day(s) |
| 3883 | SP_3883_WILL_EXPIRE_SOON | Will expire soon |
| 3884 | SP_3884_OVER_LV_D_ONLY | Over Lv %d only |
| 3885 | SP_3885_NO_LEVEL_LIMIT | No level limit |
| 3886 | SP_3886_NO_INFORMATION_ENTERED | No information entered. |
| 3887 | SP_3887_PLEASE_SELECT_THE_PACKAGE_YOULL_RECEIVE | Please select the package you'll receive. |
| 3888 | SP_3888_PACKAGE_IS_STILL_BEING_SENT_R_NPLEASE_TRY_AGAIN_LATER | Package is still being sent.\r\nPlease try again later. |
| 3889 | SP_3889_THE_ITEMS_AND_OR_MESOS_INCLUDED_IN_THE_PACKAGE_R_NWILL_BE_PERMANENTLY_DELETED_R_ | The items and/or mesos included in the package\r\nwill be permanently deleted.\r\nAre you sure you want to delete it? |
| 3890 | SP_3890_PLEASE_ENTER_THE_MESSAGE_TO_SEND | Please enter the message to send |
| 3891 | SP_3891_BY_CLICKING_HERE_100_LETTERS | by clicking here (100 letters) |
| 3892 | SP_3892_ARE_YOU_SURE_YOU_WANT_TO_CANCEL | Are you sure you want to cancel? |
| 3893 | SP_3893_YOU_DO_NOT_HAVE_THE_QUICK_DELIVERY_COUPON | You do not have the Quick Delivery Coupon. |
| 3894 | SP_3894_YOU_MUST_SELECT_EITHER_MESOS_OR_ITEMS_TO_SEND | You must select either Mesos or items to send. |
| 3895 | SP_3895_PLEASE_ENTER_THE_NAME_OF_THE_RECIPIENT | Please enter the name of the recipient. |
| 3896 | SP_3896_THE_SERVICE_CHARGE__R_NWILL_COST_D_MESOS_R_NTHIS_WILL_ALSO_USE_UP__R_N1__QUICK_D | The service charge \r\nwill cost %d mesos.\r\nThis will also use up \r\n1 [Quick Delivery Coupon].\r\nAre you sure \r\nyou want to send it? |
| 3897 | SP_3897_THE_TOTAL_WIRING_TRANSPORTATION_FEE_IS_R_ND_MESOS_R_NARE_YOU_SURE_YOU_WANT_TO_SE | The total wiring/transportation fee is\r\n%d mesos.\r\nAre you sure you want to send the package? |
| 3898 | SP_3898_HOW_MANY_DO_YOU_WISH_TO_SEND | How many do you wish to send? |
| 3899 | SP_3899_SUCCESSFULLY_DELETED | Successfully deleted. |
| 3900 | SP_3900_SUCCESSFULLY_CLAIMED | Successfully claimed. |
| 3901 | SP_3901_THE_PACKAGE_HAS_BEEN_SUCCESSFULLY_SENT | The package has been successfully sent. |
| 3902 | SP_3902_A_NEW_PACKAGE_HAS_BEEN_SENT | \tA new package has been sent. |
| 3903 | SP_3903_YOU_HAVE_MADE_AN_INCORRECT_REQUEST | You have made an incorrect request. |
| 3904 | SP_3904_PLEASE_DOUBLE_CHECK_THE_RECEIVERS_NAME | Please double-check the receiver's name. |
| 3905 | SP_3905_YOU_MAY_NOT_SEND_THE_PACKAGE_TO_A_CHARACTER__R_NUNDER_THE_SAME_ACCOUNT | You may not send the package to a character \r\nunder the same account. |
| 3906 | SP_3906_THE_RECEIVERS_PACKAGE_STORAGE_IS_FULL | The receiver's package storage is full. |
| 3907 | SP_3907_THE_CHARACTER_IS_UNABLE_TO_RECEIVE_PACKAGES | The character is unable to receive packages. |
| 3908 | SP_3908_UNABLE_TO_SEND_THE_ONE_OF_A_KIND_ITEM_R_N_DUE_TO_THE_RECIPIENT_ALREADY_POSSESSIN | Unable to send the one-of-a-kind item\r\n due to the recipient already possessing one\r\n at the Quick Delivery Inbox. |
| 3909 | SP_3909_AN_UNKNOWN_ERROR_HAS_OCCURRED | An unknown error has occurred. |
| 3910 | SP_3910_THE_INVENTORY_DOES_NOT_HAVE_ENOUGH_FREE_SLOTS_AVAILABLE_R_NPLEASE_DOUBLE_CHECK_A | The inventory does not have enough free slots available.\r\nPlease double check and try again. |
| 3911 | SP_3911_UNABLE_TO_RETRIEVE_MESOS__ITEMS__R_NDUE_TO_THE_ITEM_BEING_ONLY_ONE_OF_A_KIND | Unable to retrieve mesos & items \r\ndue to the item being only one of a kind. |
| 3912 | SP_3912_UI_BASICIMG_ICON_DELIVERY | UI/Basic.img/icon/delivery |
| 3913 | SP_3913_UI_UIWINDOWIMG_INITIALQUIZ_BACKGRND | UI/UIWindow.img/InitialQuiz/backgrnd |
| 3914 | SP_3914_UI_UIWINDOWIMG_INITIALQUIZ_NUM1_D | UI/UIWindow.img/InitialQuiz/num1/%d |
| 3915 | SP_3915_UI_UIWINDOWIMG_INITIALQUIZ_NUM1_COMMA | UI/UIWindow.img/InitialQuiz/num1/comma |
| 3916 | SP_3916_QUESTION | Question: |
| 3917 | SP_3917_CLUE | \tClue: |
| 3918 | SP_3918__ENTER_ANSWER_ | [Enter Answer] |
| 3919 | SP_3919_ENTER_AN_ANSWER_WITHIN_THE_TIME_LIMIT | Enter an answer within the time limit. |
| 3920 | SP_3920_YOU_MUST_ENTER_ATLEAST_D_LETTERS_KOREAN | You must enter atleast %d letters. (Korean) |
| 3921 | SP_3921_YOU_MUST_ENTER_LESS_THAN_D_LETTERS_KOREAN | You must enter less than %d letters. (Korean) |
| 3922 | SP_3922_YOU_CANT_DO_THIS_WHILE_TAKING_THE_QUIZ | You can't do this while taking the quiz. |
| 3923 | SP_3923_TIME_LIMIT_SURPASSED | Time limit surpassed! |
| 3924 | SP_3924_UI_UIWINDOWIMG_SPEEDQUIZ_BACKGRND | UI/UIWindow.img/SpeedQuiz/backgrnd |
| 3925 | SP_3925_UI_UIWINDOWIMG_SPEEDQUIZ_NUM1_D | UI/UIWindow.img/SpeedQuiz/num1/%d |
| 3926 | SP_3926_UI_UIWINDOWIMG_SPEEDQUIZ_NUM1_COMMA | UI/UIWindow.img/SpeedQuiz/num1/comma |
| 3927 | SP_3927_UI_UIWINDOWIMG_SPEEDQUIZ_BTNEXT | UI/UIWindow.img/SpeedQuiz/BtNext |
| 3928 | SP_3928_UI_UIWINDOWIMG_SPEEDQUIZ_BTGIVEUP | UI/UIWindow.img/SpeedQuiz/BtGiveup |
| 3929 | SP_3929_2D_PTS | %2d pts |
| 3930 | SP_3930_THE_ITEM_IS_NO_LONGER_AVAILABLE_FOR_USE_PLEASE_RE_SELL_THE_ITEM_TO_A_STORE_NEARB | The item is no longer available for use. Please re-sell the item to a store nearby. |
| 3931 | SP_3931_IT_HAS_EXCEEDED_THE_LIMIT_THAT_SYSTEM_ALLOWS | It has exceeded the limit that system allows. |
| 3932 | SP_3932_RANDACTION | randAction |
| 3933 | SP_3933__MAPLETV | /mapletv |
| 3934 | SP_3934__MAPLETV2 | /mapletv2 |
| 3935 | SP_3935__MAPLESOLETV | /maplesoletv |
| 3936 | SP_3936__MAPLELOVETV | /maplelovetv |
| 3937 | SP_3937__MEGATV | /megatv |
| 3938 | SP_3938__MEGASOLETV | /megasoletv |
| 3939 | SP_3939__MEGALOVETV | /megalovetv |
| 3940 | SP_3940_UI_MAPLETVIMG_TVMEDIA | UI/MapleTV.img/TVmedia |
| 3941 | SP_3941_UI_MAPLETVIMG_TVMEDIA_D | UI/MapleTV.img/TVmedia/%d |
| 3942 | SP_3942_UI_MAPLETVIMG_TVON | UI/MapleTV.img/TVon |
| 3943 | SP_3943_UI_MAPLETVIMG_TVOFF | UI/MapleTV.img/TVoff |
| 3944 | SP_3944_UI_MAPLETVIMG_TVBASIC_0 | UI/MapleTV.img/TVbasic/0 |
| 3945 | SP_3945_UI_MAPLETVIMG_TVCHAT_0 | UI/MapleTV.img/TVchat/0 |
| 3946 | SP_3946_UI_UIWINDOWIMG_MAPLETV | UI/UIWindow.img/MapleTV |
| 3947 | SP_3947_UI_UIWINDOWIMG_MAPLETV_BACKGRND | UI/UIWindow.img/MapleTV/backgrnd |
| 3948 | SP_3948_UI_UIWINDOWIMG_MAPLETV_BACKGRND2 | UI/UIWindow.img/MapleTV/backgrnd2 |
| 3949 | SP_3949_UI_UIWINDOWIMG_MAPLETV_BACKGRND3 | UI/UIWindow.img/MapleTV/backgrnd3 |
| 3950 | SP_3950_UI_UIWINDOWIMG_MAPLETV_BACKGRND4 | UI/UIWindow.img/MapleTV/backgrnd4 |
| 3951 | SP_3951_UI_UIWINDOWIMG_MAPLETV_BTOK | UI/UIWindow.img/MapleTV/BtOk |
| 3952 | SP_3952_UI_UIWINDOWIMG_MAPLETV_BTCANCEL | UI/UIWindow.img/MapleTV/BtCancel |
| 3953 | SP_3953_UI_UIWINDOWIMG_MAPLETV_BTTO_DISABLED_0 | UI/UIWindow.img/MapleTV/BtTo/disabled/0 |
| 3954 | SP_3954_UI_UIWINDOWIMG_MAPLETV_BTTO_ENABLED_0 | UI/UIWindow.img/MapleTV/BtTo/enabled/0 |
| 3955 | SP_3955_UI_MAPLETVIMG_TVCHAT1 | UI/MapleTV.img/TVchat1 |
| 3956 | SP_3956_UI_MAPLETVIMG_TVCHAT2 | UI/MapleTV.img/TVchat2 |
| 3957 | SP_3957_NON_GM_CHARACTER_TRIED_TO_SEND_GM_MESSAGE | Non-GM character tried to send GM message. |
| 3958 | SP_3958_THE_WAITING_LINE_IS_LONGER_THAN_AN_HOUR__R_NPLEASE_TRY_USING_IT_AT_A_LATER_TIME | The waiting line is longer than an hour. \r\nPlease try using it at a later time. |
| 3959 | SP_3959_YOUVE_ENTERED_THE_WRONG_USER_NAME | You've entered the wrong user name. |
| 3960 | SP_3960_YOU_DIDNT_ENTER_THE_RECIPIENTS_NAME | You didn't enter the recipient's name. |
| 3961 | SP_3961_THIS_MESSAGE_WILL_BE_SENT_TO__R_NMAPLE_TV_IN_D_SECONDS__R_N_DO_YOU_WANT_TO_PROCE | This message will be sent to \r\nMaple TV in %d seconds. \r\n Do you want to proceed? |
| 3962 | SP_3962_THE_RECIPIENT_IS_NOT_ONLINE | The recipient is not online. |
| 3963 | SP_3963_WOULD_YOU_LIKE_TO_USE_THE_WHITE_SCROLL | Would you like to use the White Scroll? |
| 3964 | SP_3964_THE_WHITE_SCROLL_HAS_BEEN_USED | The White Scroll has been used. |
| 3965 | SP_3965_THE_ITEM_UPGRADE_FAILED_BUT_SINCE_THE_WHITE_SCROLL_WAS_USED_THE_NUMBER_OF_ITEM_U | The item upgrade failed, but since the White Scroll was used, the number of item upgrade slots remained in tact. |
| 3966 | SP_3966__THE_ITEM_WAS_SUCCESSFULLY_UPGRADED_AND_THE_WHITE_SCROLL_WAS_USED_IN_THE_PROCESS |  The item was successfully upgraded, and the white scroll was used in the process. |
| 3967 | SP_3967_ETC_HALLOWEENIMG | Etc/Halloween.img |
| 3968 | SP_3968_PRICERECORDINGITEM | PriceRecordingItem |
| 3969 | SP_3969_DIVIDER | Divider |
| 3970 | SP_3970_ITEMTYPE | ItemType |
| 3971 | SP_3971_HALLOWEENITEM | HalloweenItem |
| 3972 | SP_3972_THE_WAITING_LINE_IS_LONGER_THAN_15_SECONDS__R_NPLEASE_TRY_USING_IT_AT_A_LATER_TI | The waiting line is longer than 15 seconds. \r\nPlease try using it at a later time. |
| 3973 | SP_3973_UI_UIWINDOWIMG_AVATARMEGAPHONE_BACKGRND | UI/UIWindow.img/AvatarMegaphone/backgrnd |
| 3974 | SP_3974_UI_UIWINDOWIMG_AVATARMEGAPHONE | UI/UIWindow.img/AvatarMegaphone |
| 3975 | SP_3975_MAP_MAPHELPERIMG_AVATARMEGAPHONE_NAME_0 | Map/MapHelper.img/AvatarMegaphone/name/0 |
| 3976 | SP_3976_MAP_MAPHELPERIMG_AVATARMEGAPHONE_NAME_1 | Map/MapHelper.img/AvatarMegaphone/name/1 |
| 3977 | SP_3977_PLAYERS_THAT_ARE_LEVEL_15_AND_BELOW__R_NMAY_ONLY_TRADE_1_MILLION_MESOS_PER_DAY__ | Players that are Level 15 and below \r\nmay only trade 1 million mesos per day. \r\nYou have reached the limit today,\r\nplease try again tomorrow. |
| 3978 | SP_3978_UI_CASHSHOPIMG_CSCHANGENAME_BASE_BACKGRND | UI/CashShop.img/CSChangeName/Base/backgrnd |
| 3979 | SP_3979_UI_CASHSHOPIMG_CSCHANGENAME_BTCHECK | UI/CashShop.img/CSChangeName/BtCheck |
| 3980 | SP_3980_UI_CASHSHOPIMG_CSCHANGENAME_BASE_BACKGRNDNOTICE | UI/CashShop.img/CSChangeName/Base/backgrndnotice |
| 3981 | SP_3981_STRING_NAMECHANGEIMG_NAMECHANGE | String/NameChange.img/NameChange |
| 3982 | SP_3982_UI_LOGINIMG_VIEWALLCHAR_SELECT | UI/Login.img/ViewAllChar/Select |
| 3983 | SP_3983_UI_LOGINIMG_VIEWALLCHAR_BTENTER | UI/Login.img/ViewAllChar/BtEnter |
| 3984 | SP_3984_UI_LOGINIMG_VIEWALLCHAR_BTCANCEL | UI/Login.img/ViewAllChar/BtCancel |
| 3985 | SP_3985_UI_LOGINIMG_VIEWALLCHAR_BTVAC | UI/Login.img/ViewAllChar/BtVAC |
| 3986 | SP_3986_UI_LOGINIMG_VIEWALLCHAR_WORLDICONS_D | UI/Login.img/ViewAllChar/WorldIcons/%d |
| 3987 | SP_3987_UI_LOGINIMG_VIEWALLCHAR_BACKGRND | UI/Login.img/ViewAllChar/backgrnd |
| 3988 | SP_3988_CANNOT_FIND_ANY_CHARACTERS | Cannot find any characters. |
| 3989 | SP_3989_YOU_ARE_ALREADY_CONNECTED_TO_SERVER | You are already connected to server. |
| 3990 | SP_3990_UNKNOWN_ERROR__VIEW_ALL_CHARACTERS | Unknown Error : View-All-Characters. |
| 3991 | SP_3991_WOULD_YOU_LIKE_TO_GIFT_THIS_ITEM_TO_SOMEONE_R_N__S__ | Would you like to gift this item to someone?\r\n[ %s ] |
| 3992 | SP_3992_YOU_DO_NOT_HAVE_ENOUGH_GIFT_TOKENS__R_NIN_YOUR_ACCOUNT_PLEASE_CHARGE_YOUR_ACCOUN | You do not have enough Gift Tokens \r\nin your account. Please charge your account \r\nwith Nexon Game Cards to receive \r\nGift Tokens to gift this item. |
| 3993 | SP_3993_DUE_TO_TECHNICAL_DIFFICULTIES_R_NTHIS_ITEM_CANNOT_BE_SENT_AT_THIS_TIME_R_NPLEASE | Due to technical difficulties,\r\nthis item cannot be sent at this time.\r\nPlease try again. |
| 3994 | SP_3994_YOU_MAY_NOT_GIFT_ITEMS_FOR__R_NIT_HAS_BEEN_LESS_THAN_TWO_WEEKS__R_NSINCE_YOU_FIR | You may not gift items for \r\nit has been less than two weeks \r\nsince you first charged your account. |
| 3995 | SP_3995_USERS_WITH_HISTORY_OF_ILLEGAL_ACTIVITIES_R_N_MAY_NOT_GIFT_ITEMS_TO_OTHERS_PLEASE | Users with history of illegal activities\r\n may not gift items to others. Please make sure \r\nyour account is neither previously blocked, \r\nnor illegally charged with NX. |
| 3996 | SP_3996_DUE_TO_LIMITATIONS__R_NYOU_MAY_NOT_GIFT_THIS_ITEM_AS_THIS_TIME__R_NPLEASE_TRY_AG | Due to limitations, \r\nyou may not gift this item as this time. \r\nPlease try again later. |
| 3997 | SP_3997_YOU_HAVE_EXCEEDED_THE_AMOUNT_OF_TIME__R_NYOU_CAN_GIFT_ITEMS_TO_OTHER_CHARACTERS | You have exceeded the amount of time \r\nyou can gift items to other characters. |
| 3998 | SP_3998_THIS_ITEM_CANNOT_BE_GIFTED__R_NDUE_TO_TECHNICAL_DIFFICULTIES__R_NPLEASE_TRY_AGAI | This item cannot be gifted \r\ndue to technical difficulties. \r\nPlease try again later. |
| 3999 | SP_3999_THE_PET_IS_EITHER_NOT_EQUIPPED_OR_THIS_ITEM_IS_NOT_APPLICABLE_FOR_YOUR_PET | The pet is either not equipped, or this item is not applicable for your pet. |
| 4000 | SP_4000_DOUBLE_CLICK_THE_ITEM_FOR_A_PREVIEW | Double-click the item for a preview. |
| 4001 | SP_4001_FROM_02D00_TO_02D00 | from %02d:00 to %02d:00 |
| 4002 | SP_4002_YOU_CANNOT_TRANSFER_A_CHARACTER_UNDER_LEVEL_20 | You cannot transfer a character under level 20. |
| 4003 | SP_4003_YOU_CANNOT_TRANSFER_A_CHARACTER_THAT__R_NIS_CURRENTLY_MARRIED | You cannot transfer a character that \r\nis currently married. |
| 4004 | SP_4004_YOU_CANNOT_TRANSFER_A_CHARACTER_THAT__R_NIS_CURRENTLY_A_GUILD_LEADER | You cannot transfer a character that \r\nis currently a Guild leader. |
| 4005 | SP_4005_YOU_CANNOT_TRANSFER_A_CHARACTER__R_NTO_THE_SAME_WORLD_IT_IS_CURRENTLY_IN | You cannot transfer a character \r\nto the same world it is currently in. |
| 4006 | SP_4006_YOU_CANNOT_TRANSFER_A_CHARACTER__R_NINTO_THE_NEW_SERVER_WORLD | You cannot transfer a character \r\ninto the new server world. |
| 4007 | SP_4007_YOU_MAY_NOT_TRANSFER_OUT_OF_THIS__R_NWORLD_AT_THIS_TIME | You may not transfer out of this \r\nworld at this time. |
| 4008 | SP_4008_YOU_CANNOT_TRANSFER_A_CHARACTER_INTO__R_NA_WORLD_THAT_HAS_NO_EMPTY_CHARACTER_SLO | You cannot transfer a character into \r\na world that has no empty character slots. |
| 4009 | SP_4009_YOU_CANNOT_TRANSFER_A_CHARACTER_IF__R_NYOUR_ACCOUNT_HAS_EVER_BEEN_BLOCKED_ONCE | You cannot transfer a character if \r\nyour account has ever been blocked once. |
| 4010 | SP_4010_YOU_CANNOT_TRANSFER_A_CHARACTER_THAT_R_N_HAS_ALREADY_BEEN_TRANSFERRED__R_NWITHIN | You cannot transfer a character that\r\n has already been transferred \r\nwithin the last 30 days. |
| 4011 | SP_4011_YOU_CANNOT_DELETE_A_CHARACTER_THAT_R_N_IS_CURRENTLY_GOING_THROUGH_THE_TRANSFER | You cannot delete a character that\r\n is currently going through the transfer. |
| 4012 | SP_4012_YOUR_CHARACTER_CANNOT_GET_MARRIED_IF_R_N_IT_IS_CURRENTLY_GOING_THROUGH_THE_TRANS | Your character cannot get married if\r\n it is currently going through the transfer. |
| 4013 | SP_4013_YOUR_CHARACTER_CANNOT_CREATE_A_GUILD_IF_R_N_IT_IS_CURRENTLY_GOING_THROUGH_THE_TR | Your character cannot create a guild if\r\n it is currently going through the transfer. |
| 4014 | SP_4014_YOU_CANNOT_REQUEST_A_TRANSFER__R_NWITH_A_CHARACTER_THAT__R_NIS_ALREADY_IN_THE_PR | You cannot request a transfer \r\nwith a character that \r\nis already in the process of transferring. |
| 4015 | SP_4015_YOU_CANNOT_TRANSFER_A_CHARACTER_IF__R_NYOUR_ACCOUNT_HAS_ALREADY_REQUESTED_FOR_A_ | You cannot transfer a character if \r\nyour account has already requested for a transfer. |
| 4016 | SP_4016_YOU_CANNOT_CREATE_A_NEW_CHARACTER__R_NUNDER_THE_ACCOUNT_THAT__R_NHAS_REQUESTED_F | You cannot create a new character \r\nunder the account that \r\nhas requested for a transfer. |
| 4017 | SP_4017_YOU_CAN_ONLY_CANCEL_THE_REQUEST__R_NWITH_A_CHARACTER_THAT_IS_CURRENTLY__R_NGOING | You can only cancel the request \r\nwith a character that is currently \r\ngoing through the transfer. |
| 4018 | SP_4018_S_IS_UNDER_THE_REQUEST__R_NFOR_TRANSFER_TO_S | %s is under the request \r\nfor transfer to %s. |
| 4019 | SP_4019_S_HAS_BEEN_TRANSFERRED__R_NFROM_S | %s has been transferred \r\nfrom %s |
| 4020 | SP_4020_THE_EVENT_HAS_EITHER_ENDED_OR_R_NTHIS_ITEM_IS_NOT_AVAILABLE_FOR_FREE_TESTING | The event has either ended or\r\nthis item is not available for free testing. |
| 4021 | SP_4021_UI_CASHSHOPIMG_CSTRANSFERWORLD_BASE_BACKGRNDNOTICE | UI/CashShop.img/CSTransferWorld/Base/backgrndnotice |
| 4022 | SP_4022_STRING_TRANSFERWORLDIMG_TRANSFERWORLD | String/TransferWorld.img/TransferWorld |
| 4023 | SP_4023_UI_CASHSHOPIMG_CSTRANSFERWORLD_BASE_BACKGRND | UI/CashShop.img/CSTransferWorld/Base/backgrnd |
| 4024 | SP_4024_EQUIPALLNEED | equipAllNeed |
| 4025 | SP_4025_EQUIPSELECTNEED | equipSelectNeed |
| 4026 | SP_4026_FIELDENTER | fieldEnter |
| 4027 | SP_4027_SELECTEDMOB | selectedMob |
| 4028 | SP_4028_BAN | ban |
| 4029 | SP_4029_BANTYPE | banType |
| 4030 | SP_4030_UI_LOGINIMG_NEWCHAR_CHARALERT | UI/Login.img/NewChar/charAlert |
| 4031 | SP_4031_YOU_CAN_CHANGE_THE | You can change the |
| 4032 | SP_4032_CHARACTER_NAME_TO | character name to |
| 4033 | SP_4033__ | ( ). |
| 4034 | SP_4034_ONCE | (once) |
| 4035 | SP_4035_MORPHEFFECT | morphEffect |
| 4036 | SP_4036_NOCANCELMOUSE | noCancelMouse |
| 4037 | SP_4037_NOSHADOW | noShadow |
| 4038 | SP_4038_SPOT | spot |
| 4039 | SP_4039_EFFECT_BASICEFFIMG_TOMBUPGRADE | Effect/BasicEff.img/TombUpgrade |
| 4040 | SP_4040_EFFECT_BASICEFFIMG_SKILLBOOK_SUCCESS_0 | Effect/BasicEff.img/SkillBook/Success/0 |
| 4041 | SP_4041_EFFECT_BASICEFFIMG_SKILLBOOK_SUCCESS_1 | Effect/BasicEff.img/SkillBook/Success/1 |
| 4042 | SP_4042_EFFECT_BASICEFFIMG_SKILLBOOK_FAILURE_0 | Effect/BasicEff.img/SkillBook/Failure/0 |
| 4043 | SP_4043_EFFECT_BASICEFFIMG_SKILLBOOK_FAILURE_1 | Effect/BasicEff.img/SkillBook/Failure/1 |
| 4044 | SP_4044_UI_UIWINDOWIMG_USERLIST_PARTY_BTCHANGEBOSS | UI/UIWindow.img/UserList/Party/BtChangeBoss |
| 4045 | SP_4045_DO_YOU_WISH_TO_PASS_THE_LEADER_POSITION_TO__R_NS | Do you wish to pass the leader position to \r\n%s? |
| 4046 | SP_4046_S_HAS_BECOME_THE_LEADER_OF_THE_PARTY | \t%s has become the leader of the party. |
| 4047 | SP_4047_DUE_TO_THE_PARTY_LEADER_DISCONNECTING_FROM_THE_GAME_S_HAS_BEEN_ASSIGNED_AS_THE_N | Due to the party leader disconnecting from the game, %s has been assigned as the new leader. |
| 4048 | SP_4048_THIS_CAN_ONLY_BE_GIVEN_TO_A_PARTY_MEMBER_WITHIN_THE_VICINITY | This can only be given to a party member within the vicinity. |
| 4049 | SP_4049_YOU_MAY_ONLY_CHANGE_WITH_THE_PARTY_MEMBER_THATS_ON_THE_SAME_CHANNEL | You may only change with the party member that's on the same channel. |
| 4050 | SP_4050_UNABLE_TO_HAND_OVER_THE_LEADERSHIP_POST_NO_PARTY_MEMBER_IS_CURRENTLY_WITHIN_THE_ | Unable to hand over the leadership post; No party member is currently within the vicinity of the party leader. |
| 4051 | SP_4051_UI_LOGINIMG_GAMEGRADE_GAMEGRADE | UI/Login.img/GameGrade/GameGrade |
| 4052 | SP_4052_MAPLINK | MapLink |
| 4053 | SP_4053_TOOLTIP | toolTip |
| 4054 | SP_4054_LINKMAP | linkMap |
| 4055 | SP_4055_LINKIMG | linkImg |
| 4056 | SP_4056_MAP_WORLDMAP_WORLDMAPIMG | Map/WorldMap/WorldMap.img |
| 4057 | SP_4057_PARENTMAP | parentMap |
| 4058 | SP_4058_NPC_07D_S_EFFECT | Npc/%07d/%s/effect |
| 4059 | SP_4059_NPC_07D_S_SOUND | Npc/%07d/%s/sound |
| 4060 | SP_4060_GAMELAUNCHEREXE | GameLauncher.exe |
| 4061 | SP_4061_EFFECT_BASICEFFIMG_QUESTALERT_APPEAR | Effect/BasicEff.img/QuestAlert/Appear |
| 4062 | SP_4062_EFFECT_BASICEFFIMG_QUESTALERT_DEFAULT | Effect/BasicEff.img/QuestAlert/Default |
| 4063 | SP_4063_AUTOSTART | autoStart |
| 4064 | SP_4064_NORMALAUTOSTART | normalAutoStart |
| 4065 | SP_4065_QUEST_ARRIVED__DOUBLE_CLICK_THE_ICON_LOCATED_ON_TOP_OF_THE_CHARACTER | Quest Arrived!  Double-click the icon located on top of the Character. |
| 4066 | SP_4066_ONESHOT | oneShot |
| 4067 | SP_4067_TIMELIMIT | timeLimit |
| 4068 | SP_4068_TIMERUI | timerUI |
| 4069 | SP_4069_UI_UIWINDOWIMG_QUEST_TIMEQUEST | UI/UIWindow.img/Quest/TimeQuest |
| 4070 | SP_4070_TIMEBAR | TimeBar |
| 4071 | SP_4071_TIMEGAGE | TimeGage |
| 4072 | SP_4072_ALARMCLOCK | AlarmClock |
| 4073 | SP_4073_QUESTCOUNT | questCount |
| 4074 | SP_4074_CLOCKBOOM | clockBoom |
| 4075 | SP_4075_TIME_LEFT_DDD | Time Left %d:%d:%d |
| 4076 | SP_4076_THE__S__QUEST_EXPIRED_BECAUSE_THE_TIME_LIMIT_ENDED | The [%s] quest expired because the time limit ended |
| 4077 | SP_4077_THIS_ITEM_CANNOT_BE_USED_HERE | This item cannot be used here. |
| 4078 | SP_4078_MAPLE_RED | Maple Red |
| 4079 | SP_4079_MAPLE_BLUE | Maple Blue |
| 4080 | SP_4080__S__HAS_BECOME_UNABLE_TO_FIGHT_AND__S_TEAM_HAS_LOST_D_CP | [%s] has become unable to fight and [%s]team has lost %d CP. |
| 4081 | SP_4081__S__HAS_BECOME_UNABLE_TO_FIGHT_BUT__S__HAS_NO_CP_SO__S__TEAM_DID_NOT_LOSE_ANY_CP | \t\t[%s] has become unable to fight but [%s] has no CP so [%s] team did not lose any CP |
| 4082 | SP_4082_YOU_DONT_HAVE_ENOUGH_CP_TO_CONTINUE | You don't have enough CP to continue. |
| 4083 | SP_4083_YOU_CAN_NO_LONGER_SUMMON_THE_MONSTER | You can no longer summon the Monster. |
| 4084 | SP_4084_YOU_CAN_NO_LONGER_SUMMON_THE_BEING | You can no longer summon the being. |
| 4085 | SP_4085_THIS_BEING_IS_ALREADY_SUMMONED | This being is already summoned. |
| 4086 | SP_4086_THIS_REQUEST_HAS_FAILED_DUE_TO_AN_UNKNOWN_ERROR | \t\tThis request has failed due to an unknown error. |
| 4087 | SP_4087_YOU_HAVE_WON_THE_MONSTER_CARNIVAL_PLEASE_WAIT_AS_YOULL_BE_TRANSPORTED_OUT_OF_HER | \tYou have won the Monster Carnival. Please wait as you'll be transported out of here shortly. |
| 4088 | SP_4088_UNFORTUNATELY_YOU_HAVE_LOST_THE_MONSTER_CARNIVAL_PLEASE_WAIT_AS_YOULL_BE_TRANSPO | Unfortunately, you have lost the Monster Carnival. Please wait as you'll be transported out of here shortly. |
| 4089 | SP_4089_DESPITE_THE_OVERTIME_THE_CARNIVAL_ENDED_IN_A_DRAW_PLEASE_WAIT_AS_YOULL_BE_TRANSP | Despite the Overtime, the carnival ended in a draw. Please wait as you'll be transported out of here shortly. |
| 4090 | SP_4090_MONSTER_CARNIVAL_HAS_ENDED_ABRUPTLY_DUE_TO_THE_OPPOSING_TEAM_LEAVING_THE_GAME_TO | Monster Carnival has ended abruptly due to the opposing team leaving the game too early. Please wait as you'll be transported out of here shortly. |
| 4091 | SP_4091__S__HAS_SUMMONED_A_BEING__S_ | [%s] has summoned a being. [%s] |
| 4092 | SP_4092__S__HAS_USED_A_SKILL__S_ | [%s] has used a skill. [%s] |
| 4093 | SP_4093__S__HAS_SUMMONED_THE_BEING__S_ | [%s] has summoned the being. [%s] |
| 4094 | SP_4094_MONSTER_CARNIVAL_IS_NOW_UNDERWAY | \tMonster Carnival is now underway!! |
| 4095 | SP_4095_BECAUSE_THE_CARNIVAL_ENDED_IN_A_DRAW_THERE_WILL_BE_A_2_MINUTE_OVERTIME | \tBecause the carnival ended in a draw, there will be a 2 minute overtime. |
| 4096 | SP_4096__S__OF_TEAM__S__HAS_QUIT_THE_MONSTER_CARNIVAL | [%s] of Team [%s] has quit the Monster Carnival. |
| 4097 | SP_4097_SINCE_THE_LEADER_OF_THE_TEAM__S__QUIT_THE_MONSTER_CARNIVAL__S__HAS_BEEN_APPOINTE | Since the leader of the Team [%s] quit the Monster Carnival, [%s] has been appointed as the new leader of the team. |
| 4098 | SP_4098_UI_UIWINDOWIMG_MONSTERCARNIVAL_ICON0 | UI/UIWindow.img/MonsterCarnival/icon0 |
| 4099 | SP_4099_UI_UIWINDOWIMG_MONSTERCARNIVAL_ICON1 | UI/UIWindow.img/MonsterCarnival/icon1 |
| 4100 | SP_4100_UI_UIWINDOWIMG_MONSTERCARNIVAL_BACKGRND2 | UI/UIWindow.img/MonsterCarnival/backgrnd2 |
| 4101 | SP_4101_UI_UIWINDOWIMG_MONSTERCARNIVAL_BACKGRND | UI/UIWindow.img/MonsterCarnival/backgrnd |
| 4102 | SP_4102_UI_UIWINDOWIMG_MONSTERCARNIVAL_BACKGRND3_TOP_0 | UI/UIWindow.img/MonsterCarnival/backgrnd3/top/0 |
| 4103 | SP_4103_UI_UIWINDOWIMG_MONSTERCARNIVAL_BACKGRND3_MIDDLE1_0 | UI/UIWindow.img/MonsterCarnival/backgrnd3/middle1/0 |
| 4104 | SP_4104_UI_UIWINDOWIMG_MONSTERCARNIVAL_BACKGRND3_BOTTOM_0 | UI/UIWindow.img/MonsterCarnival/backgrnd3/bottom/0 |
| 4105 | SP_4105_UI_UIWINDOWIMG_MONSTERCARNIVAL_BACKGRND3_MIDDLE0_0 | UI/UIWindow.img/MonsterCarnival/backgrnd3/middle0/0 |
| 4106 | SP_4106_UI_UIWINDOWIMG_MONSTERCARNIVAL_BTSIDE | UI/UIWindow.img/MonsterCarnival/BtSide |
| 4107 | SP_4107_UI_UIWINDOWIMG_MONSTERCARNIVAL_TAB_ENABLED | UI/UIWindow.img/MonsterCarnival/Tab/enabled |
| 4108 | SP_4108_UI_UIWINDOWIMG_MONSTERCARNIVAL_TAB_DISABLED | UI/UIWindow.img/MonsterCarnival/Tab/disabled |
| 4109 | SP_4109_UI_BASICIMG_BTDECIDE | UI/Basic.img/BtDecide |
| 4110 | SP_4110_PARTY_MEMBER__S | Party Member : %s |
| 4111 | SP_4111_QUESTALERT | QuestAlert |
| 4112 | SP_4112_UI_UIWINDOWIMG_MONSTERCARNIVAL_ICON2 | UI/UIWindow.img/MonsterCarnival/icon2 |
| 4113 | SP_4113_UI_UIWINDOWIMG_MONSTERCARNIVAL_ICON3 | UI/UIWindow.img/MonsterCarnival/icon3 |
| 4114 | SP_4114__CONGRATS__S_HAS_REACHED_LEVEL_200_CONGRATULATE_S_ON_SUCH_AN_AMAZING_ACHIEVEMENT | [Congrats] %s has reached Level 200! Congratulate %s on such an amazing achievement! |
| 4115 | SP_4115_UI_LOGINIMG_COMMON_SOFTKEY_BACKGRND | UI/Login.img/Common/SoftKey/backgrnd |
| 4116 | SP_4116_UI_LOGINIMG_COMMON_SOFTKEY_TAB_SELECTED | UI/Login.img/Common/SoftKey/Tab/selected |
| 4117 | SP_4117_UI_LOGINIMG_COMMON_SOFTKEY_TAB_NORMAL | UI/Login.img/Common/SoftKey/Tab/normal |
| 4118 | SP_4118_UI_LOGINIMG_COMMON_SOFTKEY_BTDEL | UI/Login.img/Common/SoftKey/BtDel |
| 4119 | SP_4119_UI_LOGINIMG_COMMON_SOFTKEY_BTNEXT | UI/Login.img/Common/SoftKey/BtNext |
| 4120 | SP_4120_UI_LOGINIMG_COMMON_SOFTKEY_BTOK | UI/Login.img/Common/SoftKey/BtOK |
| 4121 | SP_4121_UI_LOGINIMG_COMMON_SOFTKEY_BTCANCEL | UI/Login.img/Common/SoftKey/BtCancel |
| 4122 | SP_4122_UI_LOGINIMG_COMMON_SOFTKEY_BTNUM_D | UI/Login.img/Common/SoftKey/BtNum/%d |
| 4123 | SP_4123_UI_LOGINIMG_COMMON_SOFTKEY_BTLOWCASE_D | UI/Login.img/Common/SoftKey/BtLowCase/%d |
| 4124 | SP_4124_UI_LOGINIMG_COMMON_SOFTKEY_BTHIGHCASE_D | UI/Login.img/Common/SoftKey/BtHighCase/%d |
| 4125 | SP_4125_PLEASE_RE_ENTER_YOUR_PIC | Please re-enter your PIC. |
| 4126 | SP_4126_UI_LOGINIMG_COMMON_BTENABLESPW | UI/Login.img/Common/BtEnableSPW |
| 4127 | SP_4127_UI_LOGINIMG_COMMON_BTDISABLESPW | UI/Login.img/Common/BtDisableSPW |
| 4128 | SP_4128_CP_EARNED_THAT_CAN_BE_USED | CP earned that can be used. |
| 4129 | SP_4129_CP_REMAINING_D___CP_EARNED_D | CP Remaining: %d / CP Earned: %d |
| 4130 | SP_4130_THE_GAME_WILL_BE_DECIDED_BASED_ON_THE_TOTAL_CP_EARNED_BETWEEN_TWO_TEAMS | The game will be decided based on the total CP earned between two teams. |
| 4131 | SP_4131_THE_PARTY_LEADER_CAN_USE_THE_PARTY_MEMBERS_CP | The party leader can use the party members' CP |
| 4132 | SP_4132_COMPARE_THE_TOTAL_CP_EARNED_BETWEEN_TWO_TEAMS_TO_FIND_THE_WINNER | Compare the total CP earned between two teams to find the winner. |
| 4133 | SP_4133_ALLOW_S_TO_VIEW_YOUR_FRIEND_CHAT | Allow %s to view your friend chat. |
| 4134 | SP_4134_BLOCK_S_FROM_VIEWING_YOUR_FRIEND_CHAT | Block %s from viewing your friend chat. |
| 4135 | SP_4135_VIEW_S_FRIEND_CHAT | View %s' friend chat. |
| 4136 | SP_4136_DISABLE_VIEWING_SS_FRIEND_CHAT | Disable viewing %s's friend chat. |
| 4137 | SP_4137_YOU_MAY_SEND_A_MAPLEMATE_TEXT_MESSAGE_TO_S | You may send a MapleMate text message to %s |
| 4138 | SP_4138_YOU_MAY_SEND_A_REGULAR_TEXT_MESSAGE_TO_S | You may send a regular text message to %s |
| 4139 | SP_4139_S_CANNOT_LISTEN_TO_YOUR_FRIEND_CHAT | "%s" cannot listen to your friend chat. |
| 4140 | SP_4140_S_CAN_LISTEN_TO_YOUR_FRIEND_CHAT | "%s" can listen to your friend chat |
| 4141 | SP_4141_YOU_CANT_LISTEN_TO_SS_FRIEND_CHAT | \tYou can't listen to "%s"'s friend chat. |
| 4142 | SP_4142_YOU_CAN_LISTEN_TO_SS_FRIEND_CHAT | \tYou can listen to "%s"'s friend chat. |
| 4143 | SP_4143_BUDDY_CHAT_WITH_S_HAS_BEEN_DISABLED | Buddy chat with "%s" has been disabled. |
| 4144 | SP_4144_BUDDY_CHAT_WITH_S_HAS_BEEN_ENABLED | Buddy chat with "%s" has been enabled. |
| 4145 | SP_4145_MCTYPE | mcType |
| 4146 | SP_4146_SUPERMAN | superman |
| 4147 | SP_4147_MORPH | morph |
| 4148 | SP_4148_BUFF | buff |
| 4149 | SP_4149_EXCEPTBUFF | exceptbuff |
| 4150 | SP_4150_SUB_WEAPON | Sub Weapon |
| 4151 | SP_4151_FLIP | flip |
| 4152 | SP_4152_ROTATE | rotate |
| 4153 | SP_4153_FINISH | finish |
| 4154 | SP_4154_UI_UIWINDOWIMG_SKILLMACRO_BACKGRND | UI/UIWindow.img/SkillMacro/backgrnd |
| 4155 | SP_4155_UI_UIWINDOWIMG_SKILLMACRO_MACROSLOT | UI/UIWindow.img/SkillMacro/macroslot |
| 4156 | SP_4156_UI_UIWINDOWIMG_SKILLMACRO_MACROSLOT1 | UI/UIWindow.img/SkillMacro/macroslot1 |
| 4157 | SP_4157_UI_UIWINDOWIMG_SKILLMACRO_MACROSLOT2 | UI/UIWindow.img/SkillMacro/macroslot2 |
| 4158 | SP_4158_UI_UIWINDOWIMG_SKILLMACRO_MACRONAME | UI/UIWindow.img/SkillMacro/macroname |
| 4159 | SP_4159_UI_UIWINDOWIMG_SKILLMACRO_LINE01 | UI/UIWindow.img/SkillMacro/line01 |
| 4160 | SP_4160_UI_UIWINDOWIMG_SKILLMACRO_LINE02 | UI/UIWindow.img/SkillMacro/line02 |
| 4161 | SP_4161_UI_UIWINDOWIMG_SKILLMACRO_MACROICON_D_ICON | UI/UIWindow.img/SkillMacro/Macroicon/%d/icon |
| 4162 | SP_4162_UI_UIWINDOWIMG_SKILLMACROEX_BACKGRND | UI/UIWindow.img/SkillMacroEx/backgrnd |
| 4163 | SP_4163_UI_UIWINDOWIMG_SKILLMACROEX_MACROSLOT2 | UI/UIWindow.img/SkillMacroEx/macroslot2 |
| 4164 | SP_4164_UI_UIWINDOWIMG_SKILLMACROEX_MACROICON_D_ICON | UI/UIWindow.img/SkillMacroEx/Macroicon/%d/icon |
| 4165 | SP_4165_UI_UIWINDOWIMG_SKILLMACROEX_BTSAVE | UI/UIWindow.img/SkillMacroEx/BtSave |
| 4166 | SP_4166_AUTOPRECOMPLETE | autoPreComplete |
| 4167 | SP_4167__S__IT_HAS_BEEN_EXCLUDED_FROM_THE_AUTO_ALARM_AND_IT_WILL_NOT_BE_AUTOMATICALLY_RE | [%s] It has been excluded from the auto alarm and it will not be automatically reigstered until you re log-on |
| 4168 | SP_4168__S__THE_QUEST_IS_IN_PROGRESS_BUT_IT_HAS_NOT_BEEN_REGISTERED_IN_THE_ALARM | [%s] The quest is in progress but it has not been registered in the alarm. |
| 4169 | SP_4169_UI_UIWINDOWIMG_QUESTALARM_BTAUTO | UI/UIWindow.img/QuestAlarm/BtAuto |
| 4170 | SP_4170_UI_UIWINDOWIMG_QUESTALARM_BTQ | UI/UIWindow.img/QuestAlarm/BtQ |
| 4171 | SP_4171_SOMEONE_IN_MAPLESTORY_WOULD_LIKE_TO_SEND_YOU_A_MESSAGE | Someone in MapleStory would like to send you a message. |
| 4172 | SP_4172_VISUAL | visual |
| 4173 | SP_4173_SOUND | sound |
| 4174 | SP_4174_PROBABILITY | probability |
| 4175 | SP_4175_EFFECTPARAM | effectParam |
| 4176 | SP_4176_AUTOMATIC_LOCK_DISABLED | Automatic lock disabled. |
| 4177 | SP_4177_AUTOMATIC_LOCK_ENABLED | Automatic lock enabled. |
| 4178 | SP_4178_AUTO_ALARM_ON | Auto Alarm on |
| 4179 | SP_4179_AUTO_ALARM_OFF | Auto Alarm off |
| 4180 | SP_4180_WHEN_YOU_CLICK_IT_QUESTS_IN_PROGRESS_WILL_REGISTER_AUTOMATICALLY_AND_IF_IT_IS_NO | When you click it, quests in progress will register automatically and if it is not in progress for 10 minutes, it will disappear. |
| 4181 | SP_4181_WHEN_YOU_CLICK_IT_THE_QUEST_WILL_NOT_REGISTER_AUTOMATICALLY_EVEN_WHEN_THE_QUEST_ | When you click it, the quest will not register automatically even when the quest is in progress. |
| 4182 | SP_4182_THE_ZAKUM_SHRINE_WILL_CLOSE_IF_YOU_DO_NOT_SUMMON_ZAKUM_IN_D_MINUTES | The Zakum Shrine will close if you do not summon Zakum in %d minutes. |
| 4183 | SP_4183_THE_ZAKUM_SHRINE_WILL_CLOSE_IN_D_MINUTES | The Zakum Shrine will close in %d minutes. |
| 4184 | SP_4184_THE_ZAKUM_SHRINE_HAS_CLOSED | The Zakum Shrine has closed. |
| 4185 | SP_4185_THE_HORNTAILS_CAVE_WILL_CLOSE_IF_YOU_DO_NOT_SUMMON_HORNTAIL_IN_D_MINUTES | The Horntail's Cave will close if you do not summon Horntail in %d minutes. |
| 4186 | SP_4186_THE_HORNTAILS_CAVE_WILL_CLOSE_IN_D_MINUTES | The Horntail's Cave will close in %d minutes. |
| 4187 | SP_4187_THE_HORNTAILS_CAVE_HAS_CLOSED | The Horntail's Cave has closed. |
| 4188 | SP_4188_DONT_MARK | Don't mark |
| 4189 | SP_4189_MARK_NAME_ONLY | Mark name only |
| 4190 | SP_4190_MARK_HP_ONLY | Mark HP only |
| 4191 | SP_4191_MARK_NAME_AND_HP | Mark name and HP |
| 4192 | SP_4192_YOU_HAVE_SUCCESSFULLY_TURNED_IN_AN_APPLICATION | You have successfully turned in an application. |
| 4193 | SP_4193_MAPLESTORY___MICROSOFT_INTERNET_EXPLORER | MapleStory - Microsoft Internet Explorer |
| 4194 | SP_4194_MAPLESTORYTEST___MICROSOFT_INTERNET_EXPLORER | MapleStoryTest - Microsoft Internet Explorer |
| 4195 | SP_4195_YOU_MAY_NOT_USE_SCROLLS_ON_THIS_ITEM | You may not use scrolls on this item. |
| 4196 | SP_4196_UI_UIWINDOWIMG_WEDDING_INVITATION_NEAT | UI/UIWindow.img/Wedding/Invitation/neat |
| 4197 | SP_4197_UI_UIWINDOWIMG_WEDDING_INVITATION_SWEET | UI/UIWindow.img/Wedding/Invitation/sweet |
| 4198 | SP_4198_UI_UIWINDOWIMG_WEDDING_INVITATION_PREMIUM | UI/UIWindow.img/Wedding/Invitation/premium |
| 4199 | SP_4199_UI_UIWINDOWIMG_WEDDING_INVITATION_BTOK | UI/UIWindow.img/Wedding/Invitation/BtOK |
| 4200 | SP_4200_YOU_ARE_MARRIED_WITH__S | YOU ARE MARRIED WITH : %s |
| 4201 | SP_4201_YOU_ARE_ENGAGED_WITH__S | YOU ARE ENGAGED WITH : %s |
| 4202 | SP_4202_SOUND_BGMGLIMG_CHAPEL | Sound/BgmGL.img/chapel |
| 4203 | SP_4203_SOUND_BGMGLIMG_CATHEDRAL | Sound/BgmGL.img/cathedral |
| 4204 | SP_4204_DO_YOU_WANT_TO_BLESS_THIS_COUPLE | Do you want to bless this couple? |
| 4205 | SP_4205_EFFECT_BASICEFFIMG_WEDDING | Effect/BasicEff.img/Wedding |
| 4206 | SP_4206__SS | /ss |
| 4207 | SP_4207_WAITING_FOR_HER_RESPONSE | Waiting for her response... |
| 4208 | SP_4208_UI_UIWINDOWIMG_WEDDING_WISHLIST_GIVE_GROOM | UI/UIWindow.img/Wedding/wishList/give/groom |
| 4209 | SP_4209_UI_UIWINDOWIMG_WEDDING_WISHLIST_INPUT_S | UI/UIWindow.img/Wedding/wishList/input/%s |
| 4210 | SP_4210_GROOM | groom |
| 4211 | SP_4211_BRIDE | bride |
| 4212 | SP_4212_UI_UIWINDOWIMG_WEDDING_WISHLIST_INPUT_SELECT | UI/UIWindow.img/Wedding/wishList/input/select |
| 4213 | SP_4213_UI_UIWINDOWIMG_WEDDING_WISHLIST_INPUT_BTOK | UI/UIWindow.img/Wedding/wishList/input/BtOK |
| 4214 | SP_4214_UI_UIWINDOWIMG_WEDDING_WISHLIST_INPUT_BTDELETE | UI/UIWindow.img/Wedding/wishList/input/BtDelete |
| 4215 | SP_4215_UI_UIWINDOWIMG_WEDDING_WISHLIST_INPUT_BTENTER | UI/UIWindow.img/Wedding/wishList/input/BtEnter |
| 4216 | SP_4216_THIS_IS_YOUR_LAST_CHANCE_TO_MAKE_ANY_R_NCHANGES_ARE_YOU_SURE_THAT_YOU_WANT_TO_R_ | This is your last chance to make any\r\nchanges. Are you sure that you want to\r\nsubmit this list? |
| 4217 | SP_4217_UI_UIWINDOWIMG_WEDDING_WISHLIST_GIVE_SELECT | UI/UIWindow.img/Wedding/wishList/give/select |
| 4218 | SP_4218_UI_UIWINDOWIMG_WEDDING_WISHLIST_GIVE_EN | UI/UIWindow.img/Wedding/wishList/give/en |
| 4219 | SP_4219_UI_UIWINDOWIMG_WEDDING_WISHLIST_GIVE_BTEXIT | UI/UIWindow.img/Wedding/wishList/give/BtExit |
| 4220 | SP_4220_UI_UIWINDOWIMG_WEDDING_WISHLIST_GIVE_BTPUT | UI/UIWindow.img/Wedding/wishList/give/BtPut |
| 4221 | SP_4221_UI_UIWINDOWIMG_WEDDING_WISHLIST_GIVE_TAB_EN | UI/UIWindow.img/Wedding/wishList/give/Tab/En |
| 4222 | SP_4222_UI_UIWINDOWIMG_WEDDING_WISHLIST_GIVE_TAB_DS | UI/UIWindow.img/Wedding/wishList/give/Tab/Ds |
| 4223 | SP_4223_YOU_CANNOT_GIVE_MORE_THAN_ONE_PRESENT_FOR_EACH_WISHLIST | You cannot give more than one present for each wishlist. |
| 4224 | SP_4224_UI_UIWINDOWIMG_WEDDING_WISHLIST_RECEIVE_S | UI/UIWindow.img/Wedding/wishList/receive/%s |
| 4225 | SP_4225_UI_UIWINDOWIMG_WEDDING_WISHLIST_RECEIVE_SELECT | UI/UIWindow.img/Wedding/wishList/receive/select |
| 4226 | SP_4226_UI_UIWINDOWIMG_WEDDING_WISHLIST_RECEIVE_EN | UI/UIWindow.img/Wedding/wishList/receive/en |
| 4227 | SP_4227_UI_UIWINDOWIMG_WEDDING_WISHLIST_RECEIVE_BTEXIT | UI/UIWindow.img/Wedding/wishList/receive/BtExit |
| 4228 | SP_4228_UI_UIWINDOWIMG_WEDDING_WISHLIST_RECEIVE_BTGET | UI/UIWindow.img/Wedding/wishList/receive/BtGet |
| 4229 | SP_4229_UI_UIWINDOWIMG_WEDDING_WISHLIST_RECEIVE_TAB_EN | UI/UIWindow.img/Wedding/wishList/receive/Tab/En |
| 4230 | SP_4230_UI_UIWINDOWIMG_WEDDING_WISHLIST_RECEIVE_TAB_DS | UI/UIWindow.img/Wedding/wishList/receive/Tab/Ds |
| 4231 | SP_4231_ARE_YOU_SURE_YOU_WANT_TO_TAKE_IT_OUT | Are you sure you want to take it out? |
| 4232 | SP_4232_ITLL_COST_YOU_D_MESOS_TO_STORE_IT_R_NARE_YOU_SURE_YOU_WANT_TO_DO_IT | It'll cost you %d mesos to store it.\r\nAre you sure you want to do it? |
| 4233 | SP_4233_S_HAS_REQUESTED_ENGAGEMENT_R_NWILL_YOU_ACCEPT_THIS_PROPOSAL | %s has requested engagement.\r\nWill you accept this proposal? |
| 4234 | SP_4234_SIDMARRIAGEDIVORCECONFIRM | SID_MARRIAGE_DIVORCE_CONFIRM |
| 4235 | SP_4235_YOU_ARE_NOW_ENGAGED | You are now engaged. |
| 4236 | SP_4236_YOU_ARE_NOW_MARRIED | You are now married! |
| 4237 | SP_4237_SHE_HAS_POLITELY_DECLINED_YOUR_ENGAGEMENT_REQUEST | She has politely declined your engagement request. |
| 4238 | SP_4238_YOUR_ENGAGEMENT_HAS_BEEN_BROKEN | Your engagement has been broken. |
| 4239 | SP_4239_YOU_ARE_NO_LONGER_MARRIED | You are no longer married. |
| 4240 | SP_4240_YOU_HAVE_ENTERED_THE_WRONG_CHARACTER_NAME | You have entered the wrong character name. |
| 4241 | SP_4241_YOUR_PARTNER_HAS_TO_BE_IN_THE_SAME_MAP | Your partner has to be in the same map. |
| 4242 | SP_4242_YOUR_PARTNERS_ETC_SLOTS_ARE_FULL | Your partner's ETC slots are full. |
| 4243 | SP_4243_YOU_CANNOT_BE_ENGAGED_TO_THE_SAME_GENDER | You cannot be engaged to the same gender. |
| 4244 | SP_4244_YOU_ARE_ALREADY_ENGAGED | You are already engaged. |
| 4245 | SP_4245_YOU_ARE_ALREADY_MARRIED | You are already married. |
| 4246 | SP_4246_SHE_IS_ALREADY_ENGAGED | She is already engaged. |
| 4247 | SP_4247_THIS_PERSON_IS_ALREADY_MARRIED | This person is already married. |
| 4248 | SP_4248_YOURE_ALREADY_IN_MIDDLE_OR_PROPOSING_A_PERSON | You're already in middle or proposing a person. |
| 4249 | SP_4249_SHE_IS_CURRENTLY_BEING_ASKED_BY_ANOTHER_SUITOR | She is currently being asked by another suitor. |
| 4250 | SP_4250_UNFORTUNATELY_THE_MAN_WHO_PROPOSED_TO_YOU_HAS_WITHDRAWN_HIS_REQUEST_FOR_AN_ENGAG | Unfortunately, the man who proposed to you has withdrawn his request for an engagement. |
| 4251 | SP_4251_YOU_CANT_BREAK_THE_ENGAGEMENT_AFTER_MAKING_RESERVATIONS | You can't break the engagement after making reservations. |
| 4252 | SP_4252_THE_RESERVATION_HAS_BEEN_CANCELED_PLEASE_TRY_AGAIN_LATER | The reservation has been canceled. Please try again later. |
| 4253 | SP_4253_THIS_INVITATION_IS_NOT_VALID | This invitation is not valid. |
| 4254 | SP_4254_CONGRATULATIONS_R_NYOUR_RESERVATION_WAS_SUCCESSFULLY_MADE | Congratulations!\r\nYour reservation was successfully made! |
| 4255 | SP_4255_YOUR_ETC_SLOT_IS_FULL_R_NPLEASE_REMOVE_SOME_ITEMS | Your ETC slot is full.\r\nPlease remove some items. |
| 4256 | SP_4256_PLEASE_ENTER_YOUR_PARTNERS_NAME | Please enter your partner's name. |
| 4257 | SP_4257_PLEASE_ENTER_THE_GUEST_NAME | Please enter the guest name. |
| 4258 | SP_4258_YOU_MAY_NOT_SEND_THE_WEDDING_INVITATION_TO_YOURSELF_R_NYOURE_THE_ONE_GETTING_MAR | You may not send the wedding invitation to yourself.\r\nYou're the one getting married! |
| 4259 | SP_4259_DROPPING_THIS_ITEM_WILL_BREAK_YOUR_ENGAGEMENT_R_NDO_YOU_STILL_WISH_TO_DROP_THIS_ | Dropping this item will break your engagement.\r\nDo you still wish to drop this item? |
| 4260 | SP_4260_YOU_WILL_NOT_BE_ABLE_TO_RECEIVE_THE_WEDDING_RING_R_NIS_THAT_OKAY | You will not be able to receive the wedding ring.\r\nIs that okay? |
| 4261 | SP_4261_YOUR_ETC_SLOT_IS_FULL_R_NPLEASE_MAKE_SOME_SPACE_R_NTO_RECEIVE_WEDDING_INVITES | Your Etc slot is full.\r\nPlease make some space\r\nto receive Wedding Invites. |
| 4262 | SP_4262_CONGRATULATIONS__R_NYOUVE_BEEN_INVITED_TO_A_WEDDING__R_NCHECK_YOUR_ETC_SLOT | Congratulations! \r\nYou've been invited to a wedding! \r\nCheck your Etc slot. |
| 4263 | SP_4263_S_AND_SS_WEDDING_IS_GOING_TO_BE_STARTED_AT_CHAPEL_AT_CHANNEL_D | %s and %s's wedding is going to be started at Chapel at Channel %d |
| 4264 | SP_4264_S_AND_SS_WEDDING_IS_GOING_TO_BE_STARTED_AT_CATHEDRAL_AT_CHANNEL_D | %s and %s's wedding is going to be started at Cathedral at Channel %d |
| 4265 | SP_4265_S_S__20____ | %s님과 %s님의 결혼이 20세이상 채널 대성당에서 시작되려 합니다. |
| 4266 | SP_4266_YOU_MAY_ONLY_EQUIP_ONE_WEDDING_RING | \tYou may only equip ONE wedding ring. |
| 4267 | SP_4267__SIGNIFICANT_OTHER_ | [Significant Other] |
| 4268 | SP_4268_YOU_HAVE_EXCEEDED_THE_MAXIMUM_NUMBER_OF_REQUESTS | You have exceeded the maximum number of requests. |
| 4269 | SP_4269_IT_IS_NOT_APPROPRIATE_TO_USE_IT_HERE | It is not appropriate to use it here. |
| 4270 | SP_4270_DO_YOU_WANT_TO_GIFT_THIS_ITEM | Do you want to gift this item? |
| 4271 | SP_4271_YOU_HAVE_SENT_A_GIFT | You have sent a gift. |
| 4272 | SP_4272_FAILED_TO_SEND_THE_GIFT | Failed to send the gift. |
| 4273 | SP_4273_DAMAGEDBYSELECTEDMOB | damagedBySelectedMob |
| 4274 | SP_4274_DAMAGEDBYSELECTEDSKILL | damagedBySelectedSkill |
| 4275 | SP_4275_UI_UIWINDOWIMG_PLANT_BACKGRNDTOP | UI/UIWindow.img/plant/backgrndtop |
| 4276 | SP_4276_UI_UIWINDOWIMG_PLANT_BACKGRNDCENTER | UI/UIWindow.img/plant/backgrndcenter |
| 4277 | SP_4277_UI_UIWINDOWIMG_PLANT_BACKGRNDBOTTOM | UI/UIWindow.img/plant/backgrndbottom |
| 4278 | SP_4278_UI_UIWINDOWIMG_PLANT_DEFAULT_D | UI/UIWindow.img/plant/default/%d |
| 4279 | SP_4279_PLANT | plant |
| 4280 | SP_4280_THE_PLANT_HAS_YET_SPROUTED | The plant has yet sprouted. |
| 4281 | SP_4281_STEP_1_ITS_A_VERY_SMALL_SPROUT | step 1: It's a very small sprout. |
| 4282 | SP_4282_STEP_2_THE_SPROUT_HAS_GROWN_JUST_A_LITTLE_BIT | step 2: the sprout has grown just a little bit. |
| 4283 | SP_4283_STEP_3_THE_SPROUT_HAS_GROWN_A_BIT | step 3: the sprout has grown a bit. |
| 4284 | SP_4284_STEP_4_THE_SPOURT_HAS_GROWN_A_LOT | step 4: the spourt has grown a lot. |
| 4285 | SP_4285_STEP_5_THE_SPROUT_HAS_GROWN_VERY_MUCH | step 5: the sprout has grown very much. |
| 4286 | SP_4286_STEP_6_THE_PLANT_SOON_SOON_BEAR_FRUIT | step 6: The plant soon soon bear fruit. |
| 4287 | SP_4287_STEP_7_THE_PLANT_HAS_BORE_FRUIT | step 7: The plant has bore fruit. |
| 4288 | SP_4288_SPECTRUM | spectrum |
| 4289 | SP_4289_ALPHA | alpha |
| 4290 | SP_4290_UI_UIWINDOWIMG_USERLIST_FRIEND_BTMATE | UI/UIWindow.img/UserList/Friend/BtMate |
| 4291 | SP_4291_UI_UIWINDOWIMG_MATEMESSAGE_TOP | UI/UIWindow.img/MateMessage/top |
| 4292 | SP_4292_UI_UIWINDOWIMG_MATEMESSAGE_CENTER | UI/UIWindow.img/MateMessage/center |
| 4293 | SP_4293_UI_UIWINDOWIMG_MATEMESSAGE_BOTTOM | UI/UIWindow.img/MateMessage/bottom |
| 4294 | SP_4294_UI_UIWINDOWIMG_MATEMESSAGE_BTSEND | UI/UIWindow.img/MateMessage/BtSend |
| 4295 | SP_4295_UI_UIWINDOWIMG_MATEMESSAGE_TEXT | UI/UIWindow.img/MateMessage/text |
| 4296 | SP_4296_NOT_STARTED | Not Started |
| 4297 | SP_4297_IN_PROGRESS | In Progress |
| 4298 | SP_4298_ENDED | Ended |
| 4299 | SP_4299_PRE_COMPLETION_ENABLED | Pre-completion enabled |
| 4300 | SP_4300_INFONUMBER | infoNumber |
| 4301 | SP_4301_YOU_MAY_NOT_OPEN_THE_STORE_WHILE_JUMPING | You may not open the store while jumping. |
| 4302 | SP_4302_RECOMMENDED_JOB_ | Recommended Job : |
| 4303 | SP_4303_THIS_JOB_USES_POWERFUL_PHYSICAL_ATTACKS_AND_TREMENDOUS | This job uses powerful physical attacks and tremendous |
| 4304 | SP_4304_INNER_STRENGTH_TO_TACKLE_ALL_KINDS_OF_CHALLENGES_HEAD_ON | inner strength to tackle all kinds of challenges head-on. |
| 4305 | SP_4305_HEAVILY_DEPENDENT_ON_STR_AND_DEX | Heavily dependent on STR and DEX. |
| 4306 | SP_4306_______ | 단련된 신체와 힘으로 모든 시련을 정면으로 맞서 |
| 4307 | SP_4307___STR_DEX__ | 돌파하는 직업. STR과 DEX의 영향을 받는다. |
| 4308 | SP_4308_THIS_JOB_USES_EXTRAORDINARY_MAGIC_POWER_TO_VANQUISH_ENEMIES | This job uses extraordinary magic power to vanquish enemies. |
| 4309 | SP_4309_CAN_BE_SUPPORTIVE_OR_OFFENSIVE | Can be supportive or offensive. |
| 4310 | SP_4310_HEAVILY_DEPENDENT_ON_LUK_AND_INT | Heavily dependent on LUK and INT. |
| 4311 | SP_4311_THIS_JOB_USES_INCREDIBLE_CONCENTRATION_AND_EXCEPTIONAL | This job uses incredible concentration and exceptional |
| 4312 | SP_4312_ACCURACY_FOR_LONG_RANGE_ATTACKS_WITH_EASE | accuracy for long-range attacks with ease. |
| 4313 | SP_4313_HEAVILY_DEPENDENT_ON_DEX_AND_STR | Heavily dependent on DEX and STR. |
| 4314 | SP_4314______ | 숙련된 집중력을 바탕으로 원거리 무기를 자유자재로 |
| 4315 | SP_4315___DEX_STR__ | 다루는 직업. DEX와 STR의 영향을 받는다. |
| 4316 | SP_4316_THIS_JOB_USES_SUPREME_SPEED_AND_STEALTH_TACTICS | This job uses supreme speed and stealth tactics |
| 4317 | SP_4317_TO_OVERWHELM_FOES_SUITABLE_FOR_DANGEROUS_ADVENTURES | to overwhelm foes, suitable for dangerous adventures. |
| 4318 | SP_4318_HEAVILY_DEPENDENT_ON_LUK_AND_DEX | Heavily dependent on LUK and DEX. |
| 4319 | SP_4319_ | ?? |
| 4320 | SP_4320_THERES_NO_JOB_AVAILABLE_THAT_SUITS_THESE_STATS | There's no job available that suits these stats. |
| 4321 | SP_4321_IF_YOU_WANT_TO_RESET_THESE_STATS_RANDOMLY_ROLL_THE_DICE_AGAIN | If you want to reset these stats randomly, roll the dice again! |
| 4322 | SP_4322_ROLL | Roll |
| 4323 | SP_4323_THE_DICE | the dice! |
| 4324 | SP_4324_PLACE_THE_MOUSE_CURSOR | Place the mouse cursor |
| 4325 | SP_4325_OVER_THE_SKILL_ICON | over the skill icon |
| 4326 | SP_4326_FOR_DETAILS_ABOUT | for details about |
| 4327 | SP_4327_A_SPECIFIC_SKILL | a specific skill. |
| 4328 | SP_4328_CLICK_ON_THE | Click on the |
| 4329 | SP_4329_BUTTON_TO_ASSIGN | button to assign |
| 4330 | SP_4330_THE_SP_AND_RAISE | the SP and raise |
| 4331 | SP_4331_THE_SKILL_LEVEL | the skill level. |
| 4332 | SP_4332_YOU_MAY_RAISE | You may raise |
| 4333 | SP_4333_YOUR_SKILL_LEVELS | your skill levels |
| 4334 | SP_4334_BY_ASSIGNING_THE_SP | by assigning the SP |
| 4335 | SP_4335_YOU_HAVE_ON_THE_SKILLS | you have on the skills. |
| 4336 | SP_4336_REGISTER_THE_FREQUENTLY_USED_SKILLS_FOR_A_QUICK_ACCESS_WITH_A_SINGLE_BUTTON | Register the frequently-used skills for a quick access with a single button. |
| 4337 | SP_4337__R8_ | [R8] |
| 4338 | SP_4338_TERMSTART | termStart |
| 4339 | SP_4339_TERMEND | termEnd |
| 4340 | SP_4340_ONLYONCE | onlyOnce |
| 4341 | SP_4341_YOU_MAY_OPEN_CLOSE_YOUR_OWN_SKILL_WINDOW | You may open/close your own skill window. |
| 4342 | SP_4342_DRAG_THE_SKILL_ICON_FROM_THE_SKILL_INVENTORY_R_NON_THE_LEFT_INTO_THE_SLOTS | Drag the skill icon from the skill inventory\r\non the left into the slots. |
| 4343 | SP_4343_SAVING_THE_SKILL_NAME | Saving the skill name. |
| 4344 | SP_4344_UI_BASICIMG_BTMACRO | UI/Basic.img/BtMacro |
| 4345 | SP_4345_UI_UIWINDOWIMG_SKILLMACROEX_BTMACROIN | UI/UIWindow.img/SkillMacroEx/BtMacroIn |
| 4346 | SP_4346_UI_UIWINDOWIMG_SKILLMACROEX_BTMACROOUT | UI/UIWindow.img/SkillMacroEx/BtMacroOut |
| 4347 | SP_4347_REMOVER | remover |
| 4348 | SP_4348_YOU_MAY_NOT_CANCEL_THE_TRANSFORMATION | You may not cancel the transformation. |
| 4349 | SP_4349_UNABLE_TO_CAPTURE_THE_MONSTER_THE_MONSTER_IS_TOO_STRONG | Unable to capture the monster; the monster is too strong. |
| 4350 | SP_4350_ELEMENT_ROCK_CANNOT_BE_USED_RIGHT_AFTER_USAGE | Element Rock cannot be used right after usage. |
| 4351 | SP_4351_UNABLE_TO_CAPTURE_THE_MONSTER_THE_GHOST_IS_TOO_FAR | Unable to capture the monster; the ghost is too far. |
| 4352 | SP_4352_UNABLE_TO_CAPTURE_THE_MONSTER_THE_MONSTER_IS_TOO_FAR | Unable to capture the monster; the monster is too far. |
| 4353 | SP_4353_HYBRID | hybrid |
| 4354 | SP_4354_GRADE | grade |
| 4355 | SP_4355_MESSAGE | message |
| 4356 | SP_4356_CONSUMEITEM | consumeItem |
| 4357 | SP_4357_UIDATA | uiData |
| 4358 | SP_4358_QUESTID | questId |
| 4359 | SP_4359_UI_UIWINDOWIMG_RAISE_BACKGRND_TOP | UI/UIWindow.img/raise/backgrnd/top |
| 4360 | SP_4360_UI_UIWINDOWIMG_RAISE_BACKGRND_CENTER | UI/UIWindow.img/raise/backgrnd/center |
| 4361 | SP_4361_UI_UIWINDOWIMG_RAISE_BACKGRND_BOTTOM | UI/UIWindow.img/raise/backgrnd/bottom |
| 4362 | SP_4362_THIS_ITEM_IS_NOT_AVAILABLE | This item is not available. |
| 4363 | SP_4363_THE_TOTAL_PRICE_FOR_THE_ITEMS_EXCEEDED_R_NTHE_MAXIMUM_AMOUNT_OF_MESOS_THAT_CAN_B | The total price for the items exceeded\r\nthe maximum amount of mesos that can be sold. |
| 4364 | SP_4364_WEATHER | weather |
| 4365 | SP_4365_OPTION | option |
| 4366 | SP_4366_UI_CHATBALLOONIMG_NPC | UI/ChatBalloon.img/npc |
| 4367 | SP_4367_ITEMCODE | itemCode |
| 4368 | SP_4368_ITEMRANGE | itemRange |
| 4369 | SP_4369_YOU_ARE_THE_ONLY_ONE_LEFT_IN_THE_ARENA_HUNTING_COMPETITION_WILL_NOW_CONCLUDE | You are the only one left in the Arena; Hunting Competition will now conclude. |
| 4370 | SP_4370_UI_UIWINDOWIMG_ARIANTMATCH_CHARACTERICON_D | UI/UIWindow.img/AriantMatch/characterIcon/%d |
| 4371 | SP_4371_UI_UIWINDOWIMG_ARIANTMATCH_RESULT | UI/UIWindow.img/AriantMatch/Result |
| 4372 | SP_4372_SCROLLOPEN | ScrollOpen |
| 4373 | SP_4373_MOBTYPE | mobType |
| 4374 | SP_4374_THIS_SKILL_CANNOT_BE_USED_WITH_THE_EQUIPPED_ITEM | This skill cannot be used with the equipped item. |
| 4375 | SP_4375_IT_CANNOT_BE_USED_HERE | It cannot be used here. |
| 4376 | SP_4376_THE_DIVORCE_HAS_BEEN_FINALIZED_AND_THE_WEDDING_RING_HAS_DISAPPEARED | The divorce has been finalized, and the wedding ring has disappeared. |
| 4377 | SP_4377_4D | %4d |
| 4378 | SP_4378_TO_HATCH_PIGMY_EGG_YOU_NEED_TO_R_NPURCHASE_THE_INCUBATOR_FROM_THE_CASH_SHOP | To hatch Pigmy Egg, you need to\r\npurchase the incubator from the Cash Shop. |
| 4379 | SP_4379_STRING_EQPIMG | String/Eqp.img |
| 4380 | SP_4380_STRING_CONSUMEIMG | String/Consume.img |
| 4381 | SP_4381_STRING_INSIMG | String/Ins.img |
| 4382 | SP_4382_STRING_CASHIMG | String/Cash.img |
| 4383 | SP_4383_STRING_PETIMG | String/Pet.img |
| 4384 | SP_4384_STRING_ETCIMG | String/Etc.img |
| 4385 | SP_4385_IMAGE | image |
| 4386 | SP_4386_PH | ph |
| 4387 | SP_4387_PSH | psh |
| 4388 | SP_4388_PORTALSTART | portalStart |
| 4389 | SP_4389_PORTALCONTINUE | portalContinue |
| 4390 | SP_4390_PORTALEXIT | portalExit |
| 4391 | SP_4391_UI_UIWINDOWIMG_EVENTWINDOW | UI/UIWindow.img/EventWindow |
| 4392 | SP_4392_UI_UIWINDOWIMG_EVENTWINDOW_BACKGRND | UI/UIWindow.img/EventWindow/backgrnd |
| 4393 | SP_4393_UI_UIWINDOWIMG_EVENTWINDOW_BACKGRND_TOP | UI/UIWindow.img/EventWindow/backgrnd/top |
| 4394 | SP_4394_UI_UIWINDOWIMG_EVENTWINDOW_BACKGRND_CENTER | UI/UIWindow.img/EventWindow/backgrnd/center |
| 4395 | SP_4395_UI_UIWINDOWIMG_EVENTWINDOW_BACKGRND_BOTTOM | UI/UIWindow.img/EventWindow/backgrnd/bottom |
| 4396 | SP_4396_CENTER | center |
| 4397 | SP_4397_MINIMAP | minimap |
| 4398 | SP_4398_USER | user |
| 4399 | SP_4399_ANOTHER | another |
| 4400 | SP_4400_FRIEND | friend |
| 4401 | SP_4401_GUILD | guild |
| 4402 | SP_4402_GUILDMASTER | guildmaster |
| 4403 | SP_4403_MATCH | match |
| 4404 | SP_4404_PARTYMASTER | partymaster |
| 4405 | SP_4405_STARTNPC | startnpc |
| 4406 | SP_4406_ENDNPC | endnpc |
| 4407 | SP_4407_USERTRADER | usertrader |
| 4408 | SP_4408_ANOTHERTRADER | anothertrader |
| 4409 | SP_4409_ARROWUPLEFT | arrowupleft |
| 4410 | SP_4410_ARROWUP | arrowup |
| 4411 | SP_4411_ARROWUPRIGHT | arrowupright |
| 4412 | SP_4412_ARROWDOWNLEFT | arrowdownleft |
| 4413 | SP_4413_ARROWDOWN | arrowdown |
| 4414 | SP_4414_ARROWDOWNRIGHT | arrowdownright |
| 4415 | SP_4415_ARROWLEFT | arrowleft |
| 4416 | SP_4416_ARROWRIGHT | arrowright |
| 4417 | SP_4417_FORBIDFALLDOWN | forbidFallDown |
| 4418 | SP_4418_NO_TARGET_AVAILABLE | No target available. |
| 4419 | SP_4419_EFFECT_MAPEFFIMG_NPCSUMMON | Effect/MapEff.img/NpcSummon |
| 4420 | SP_4420_EFFECT_MAPEFFIMG_NPCRETURN | Effect/MapEff.img/NpcReturn |
| 4421 | SP_4421_USE_THE_TAB_KEY_TO_TOGGLE_THE_MENU | Use the Tab key to toggle the menu. |
| 4422 | SP_4422_GAUGEBAR | GaugeBar |
| 4423 | SP_4423_GRADUATION | graduation |
| 4424 | SP_4424_BAR | bar |
| 4425 | SP_4425_HTTP__SNEXONCOM_MAPLESTORY_PAGE_GNXGUILDBOARDASPXURLGUILDBOARD_GUILDLOGINAUTHKEY | http://%s.nexon.com/maplestory/page/Gnxguildboard.aspx?URL=GuildBoard/GuildLoginAuth&key= |
| 4426 | SP_4426_HTTP__SNEXONCOM_MAPLESTORY_PAGE_GNXGUILDBOARDASPXURLGUILDBOARD_GUILDBOARDOIDARTI | http://%s.nexon.com/maplestory/page/Gnxguildboard.aspx?URL=GuildBoard/GuildBoard&oidArticle=%d&codeBoard=%d&maskPageType=%d |
| 4427 | SP_4427_HTTP__SNEXONCOM_MAPLESTORY_PAGE_GNXGUILDBOARDASPXURLGUILDBOARD_GUILDBOARDCODEBOA | http://%s.nexon.com/maplestory/page/Gnxguildboard.aspx?URL=GuildBoard/GuildBoard&codeBoard=%d&maskPageType=%d |
| 4428 | SP_4428_GUILDBOARDMAPLESTORY | guildboard.maplestory |
| 4429 | SP_4429_MAPLESTORYT | maplestoryt |
| 4430 | SP_4430__S_S | [%s]%s |
| 4431 | SP_4431_DUE_TO_THE_POTENCY_OF_THE_SCROLL_R_NTHE_SEALED_ITEM_MIGHT_BE_DESTROYED_R_NARE_YO | Due to the potency of the scroll,\r\nthe sealed item might be destroyed.\r\nAre you sure you still want to use the scroll? |
| 4432 | SP_4432_IT_CAN_NOT_BE_USED_ON_THIS_EQUIPMENT_ANYMORE | It can not be used on this equipment anymore. |
| 4433 | SP_4433_SHOWPARTYHP | ShowPartyHP |
| 4434 | SP_4434_YOU_HAVE_NOT_SELECTED_AN_ITEM_R_NARE_YOU_SURE_YOU_STILL_WANT_TO_USE_THE_ITEM | You have not selected an item.\r\nAre you sure you still want to use the item? |
| 4435 | SP_4435_EMISSION | emission |
| 4436 | SP_4436_THETA | theta |
| 4437 | SP_4437_GENONMOVE | genOnMove |
| 4438 | SP_4438_GENPOINT | genPoint |
| 4439 | SP_4439_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDBOARD_BACKGRND1 | UI/UIWindow.img/UserList/Guild/GuildBoard/backgrnd1 |
| 4440 | SP_4440_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDBOARD_BTSAVE | UI/UIWindow.img/UserList/Guild/GuildBoard/BtSave |
| 4441 | SP_4441_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDBOARD_BTCANCEL | UI/UIWindow.img/UserList/Guild/GuildBoard/BtCancel |
| 4442 | SP_4442_YOU_MAY_NOT_ENTER_MORE_THAN_20_LINES | You may not enter more than 20 lines. |
| 4443 | SP_4443_YOU_MAY_ENTER_UP_TO_300_LETTERS | You may enter up to 300 letters. |
| 4444 | SP_4444_THE_TITLE_CAN_BE_UP_TO_34_LETTERS | The title can be up to 34 letters. |
| 4445 | SP_4445_UI_UIWINDOWIMG_USERLIST_GUILD_GUILDBOARD_BACKGRND2 | UI/UIWindow.img/UserList/Guild/GuildBoard/backgrnd2 |
| 4446 | SP_4446_THE_REPLY_CAN_BE_UP_TO_68_LETTERS | The reply can be up to 68 letters. |
| 4447 | SP_4447_S_GUILD_IS_CURRENTLY_BLOCKING_ALL_GUILD_UNION_REQUESTS | %s Guild is currently blocking all Guild Union requests. |
| 4448 | SP_4448_S_GUILD_HAS_REJECTED_THE_GUILD_UNION_INVITATION | %s Guild has rejected the Guild Union invitation. |
| 4449 | SP_4449_S_GUILD_IS_ALREADY_SENDING_GUILD_UNION_INVITATIONS | %s Guild is already sending Guild Union invitations. |
| 4450 | SP_4450_THIS_GUILD_IS_NOT_AFFILIATED_WITH_ANY_GUILD_UNION | This guild is not affiliated with any Guild Union. |
| 4451 | SP_4451_GUILD_UNION_NOTICE | Guild Union notice. |
| 4452 | SP_4452_UI_UIWINDOWIMG_USERLIST_GUILDUNION | UI/UIWindow.img/UserList/GuildUnion |
| 4453 | SP_4453_PLEASE_ENTER_THE_GUILD_NAME_R_NTO_SEND_THE_INVITATION_FOR_THE_UNION | Please enter the Guild name\r\nto send the invitation for the Union. |
| 4454 | SP_4454_ARE_YOU_SURE_YOU_WANT_TO_LEAVE_THE_UNION | Are you sure you want to leave the Union? |
| 4455 | SP_4455_DO_YOU_WISH_TO_REMOVE__S__GUILD_R_NFROM_THE_UNION | Do you wish to remove [%s] Guild\r\nfrom the Union? |
| 4456 | SP_4456_ENTER_THE_GUILD_UNION_NOTICE | Enter the Guild Union notice. |
| 4457 | SP_4457_YOU_HAVE_EXCEEDED_THE_MAXIMUM_NUMBER_R_NOF_USAGE_PER_ACCOUNT_FOR_THIS_ACCOUNT_R_ | You have exceeded the maximum number\r\nof usage per account\\for this account.\r\nPlease check the coupon for detail. |
| 4458 | SP_4458_HELLO | Hello! |
| 4459 | SP_4459_UNABLE_TO_DELETE_DUE_TO_A_MEMBER_R_NIN_THE_UNION_CURRENTLY_BEING_ON_THE_POSITION | Unable to delete due to a member\r\nin the Union currently being on the position. |
| 4460 | SP_4460_THE_DOOR_THAT_CONNECTS_TO_THE_WORLD_IS_CLOSED_YOU_CANNOT_USE_THE_BRIDGE_AT_THE_M | The door that connects to the world is closed. You cannot use the bridge at the moment. |
| 4461 | SP_4461_UI_UIWINDOWIMG_ARTMEGAPHONE_BTEX | UI/UIWindow.img/ArtMegaphone/BtEx |
| 4462 | SP_4462_UI_UIWINDOWIMG_ARTMEGAPHONE_BTPREVIEW | UI/UIWindow.img/ArtMegaphone/BtPreView |
| 4463 | SP_4463_ITEM_CASH_0507IMG_08D_UI | Item/Cash/0507.img/%08d/ui |
| 4464 | SP_4464_ITEM_CASH_0507IMG_08D_UI_D | Item/Cash/0507.img/%08d/ui/%d |
| 4465 | SP_4465_ITEM_CASH_0507IMG_08D_SAMPLE_D | Item/Cash/0507.img/%08d/sample/%d |
| 4466 | SP_4466_THIS_IS_A_PREVIEW_OPTION_FOR_THE_TRIPLE_MEGAPHONE | This is a preview option for the Triple Megaphone. |
| 4467 | SP_4467_ONE_OF_THE_LINES_HAS_NOT_BEEN_APPLIED_R_NWOULD_YOU_STILL_LIKE_TO_USE_IT | One of the lines has not been applied.\r\nWould you still like to use it? |
| 4468 | SP_4468_JRANK | #jrank# |
| 4469 | SP_4469_JTRY | #jtry# |
| 4470 | SP_4470_JVIC | #jvic# |
| 4471 | SP_4471_JCMP | #jcmp# |
| 4472 | SP_4472_JLOSE | #jlose# |
| 4473 | SP_4473_JDRAW | #jdraw# |
| 4474 | SP_4474_JMIN | #jmin# |
| 4475 | SP_4475_JGVUP | #jgvup# |
| 4476 | SP_4476_JHAVE | #jhave |
| 4477 | SP_4477_JSEC | #jsec# |
| 4478 | SP_4478_JPOP | #jpop# |
| 4479 | SP_4479_JPOPGAP | #jpopgap# |
| 4480 | SP_4480_JLEVEL | #jlevel# |
| 4481 | SP_4481_JSCNT | #jscnt# |
| 4482 | SP_4482_JCMPCNT | #jcmpcnt# |
| 4483 | SP_4483_JGAUGEPQS | #jgaugePqS# |
| 4484 | SP_4484_JGAUGEQCNT | #jgaugeQCnt# |
| 4485 | SP_4485_JGAUGEDAYS | #jgaugeDays# |
| 4486 | SP_4486_JGAUGEPOP | #jgaugePop# |
| 4487 | SP_4487_JGAUGEHUNT | #jgaugeHunt# |
| 4488 | SP_4488_JPERPQS | #jperPqS# |
| 4489 | SP_4489_JPERQCNT | #jperQCnt# |
| 4490 | SP_4490_JPERDAYS | #jperDays# |
| 4491 | SP_4491_JPERPOP | #jperPop# |
| 4492 | SP_4492_JPERHUNT | #jperHunt# |
| 4493 | SP_4493_ACQUIRE | Acquire |
| 4494 | SP_4494_FAIL | Fail |
| 4495 | SP_4495_UI_BASICIMG_PQUESTRANK_S | UI/Basic.img/PQuestRank/%s |
| 4496 | SP_4496_PARTY_QUEST | Party Quest |
| 4497 | SP_4497_CHAIN_QUEST | Chain Quest |
| 4498 | SP_4498_UI_UIWINDOWIMG_ITEM_FULLBACKGRND | UI/UIWindow.img/Item/FullBackgrnd |
| 4499 | SP_4499_UI_UIWINDOWIMG_ITEM_BACKGRND | UI/UIWindow.img/Item/backgrnd |
| 4500 | SP_4500_UI_UIWINDOWIMG_ITEM_BTCASHSHOP | UI/UIWindow.img/Item/BtCashshop |
| 4501 | SP_4501_EXPAND_INVENTORY | Expand Inventory |
| 4502 | SP_4502_COLLAPSE_INVENTORY | Collapse Inventory |
| 4503 | SP_4503_INVENTORYEXPANDED | Inventory_expanded |
| 4504 | SP_4504_YOU_CAN_EXPAND_THE_INVENTORY_SLOT_WITH_CASH_ITEMS | You can expand the Inventory slot with cash items. |
| 4505 | SP_4505_UI_UIWINDOWIMG_SLIDEMENU_D | UI/UIWindow.img/SlideMenu/%d |
| 4506 | SP_4506_UI_UIWINDOWIMG_SLIDEMENU_D_BACKGRD | UI/UIWindow.img/SlideMenu/%d/backgrd |
| 4507 | SP_4507_UI_UIWINDOWIMG_SLIDEMENU_D_BTMAIN_D | UI/UIWindow.img/SlideMenu/%d/BtMain/%d |
| 4508 | SP_4508_UI_UIWINDOWIMG_SLIDEMENU_D_BTARROW_LEFT | UI/UIWindow.img/SlideMenu/%d/BtArrow/left |
| 4509 | SP_4509_UI_UIWINDOWIMG_SLIDEMENU_D_BTARROW_RIGHT | UI/UIWindow.img/SlideMenu/%d/BtArrow/right |
| 4510 | SP_4510_UI_UIWINDOWIMG_SLIDEMENU_D_BTMOVE | UI/UIWindow.img/SlideMenu/%d/BtMove |
| 4511 | SP_4511_UI_UIWINDOWIMG_SLIDEMENU_D_BTCANCLE | UI/UIWindow.img/SlideMenu/%d/BtCancle |
| 4512 | SP_4512_UI_UIWINDOWIMG_SLIDEMENU_D_CHOICE | UI/UIWindow.img/SlideMenu/%d/Choice |
| 4513 | SP_4513_UI_UIWINDOWIMG_SLIDEMENU_D_COVER | UI/UIWindow.img/SlideMenu/%d/Cover |
| 4514 | SP_4514_ETC_RECOMMENDSKILLIMG_D | Etc/RecommendSkill.img/%d |
| 4515 | SP_4515_UI_UIWINDOWIMG_COUNSEL_BACKGRND | UI/UIWindow.img/counsel/backgrnd |
| 4516 | SP_4516_UI_UIWINDOWIMG_COUNSEL_BTWRITE | UI/UIWindow.img/counsel/BtWrite |
| 4517 | SP_4517_UI_UIWINDOWIMG_COUNSEL_BTMODIFY | UI/UIWindow.img/counsel/BtModify |
| 4518 | SP_4518_UI_UIWINDOWIMG_COUNSEL_BTDELETE | UI/UIWindow.img/counsel/BtDelete |
| 4519 | SP_4519_UI_UIWINDOWIMG_COUNSEL_LOADING | UI/UIWindow.img/counsel/Loading |
| 4520 | SP_4520_HTTP__GUILDBOARDMAPLESTORYNEXONCOM_MAPLESTORY_PAGE_GNXGUILDBOARDASPXURLCONSULTBO | http://guildboard.maplestory.nexon.com/maplestory/page/Gnxguildboard.aspx?URL=ConsultBoard/ConsultLoginAuth&key=%s%s |
| 4521 | SP_4521_MASKPAGETYPE | maskPageType= |
| 4522 | SP_4522_OIDARTICLE | oidArticle= |
| 4523 | SP_4523_ANSWER | answer |
| 4524 | SP_4524_HTTP__GUILDBOARDMAPLESTORYNEXONCOM_MAPLESTORY_PAGE_GNXGUILDBOARDASPXURLCONSULTBO | http://guildboard.maplestory.nexon.com/maplestory/page/GnxGuildBoard.aspx?URL=ConsultBoard/ConsultBoard&maskPageType=1 |
| 4525 | SP_4525_HTTP__GUILDBOARDMAPLESTORYNEXONCOM_MAPLESTORY_PAGE_GNXGUILDBOARDASPXURLCONSULTBO | http://guildboard.maplestory.nexon.com/maplestory/page/GnxGuildBoard.aspx?URL=ConsultBoard/ConsultBoard&oidArticle=%d&maskPageType=2&n4PageNo=1 |
| 4526 | SP_4526_DO_YOU_REALLY_WANT_TO_DELETE | Do you really want to delete? |
| 4527 | SP_4527_CONNECTION_HAS_BEEN_DELAYED_R_NDUE_TO_NETWORK_OVERLOAD_R_NPLEASE_TRY_AGAIN_LATER | Connection has been delayed\r\ndue to network overload.\r\nPlease try again later. |
| 4528 | SP_4528_YOU_CAN_EXPAND_D_SLOTS_WHEN_R_NYOU_CHANGE_OCCUPATIONS_R_NWOULD_YOU_REALLY_LIKE_T | You can expand %d slots when\r\nyou change occupations.\r\nWould you really like to\r\nexpand now even at a cost? |
| 4529 | SP_4529_UI_UIWINDOWIMG_ITEM_DISABLED | UI/UIWindow.img/Item/disabled |
| 4530 | SP_4530_SORTKEY | sortkey |
| 4531 | SP_4531_A_TOTAL_OF_D_MAPS_HAVE_BEEN_FOUND | A total of %d maps have been found. |
| 4532 | SP_4532_NO_MAPS_HAVE_BEEN_FOUND | No maps have been found. |
| 4533 | SP_4533_YOU_WILL_NOW_MOVE_TO__D__S | You will now move to [%d] %s. |
| 4534 | SP_4534_D__D__S | %d [%d] %s |
| 4535 | SP_4535_S_WORLD__S_HAS_BEEN_SELECTED_FOR_S_LETS_CONGRATULATE_HIM_HER | %s world : %s has been selected for %s. Let's congratulate him/her! |
| 4536 | SP_4536_EITHER_YOU_DONT_HAVE_ENOUGH_MESOS_OR_S | Either you don't have enough Mesos or %s |
| 4537 | SP_4537_YOU_CANNOT_SEND_A_GIFT_TO_YOUR_OWN_ACCOUNT_R_NPLEASE_PURCHASE_IT_AFTER_LOGGING_R | You cannot send a gift to your own account.\r\nPlease purchase it after logging\r\nin with the related character. |
| 4538 | SP_4538_PLEASE_CONFIRM_WHETHER_R_NTHE_CHARACTERS_NAME_IS_CORRECT | Please confirm whether\r\nthe character's name is correct. |
| 4539 | SP_4539_THIS_ITEM_HAS_A_GENDER_RESTRICTION_R_NPLEASE_CONFIRM_THE_GENDER_OF_THE_RECIPIENT | This item has a gender restriction.\r\nPlease confirm the gender of the recipient. |
| 4540 | SP_4540_THE_GIFT_CANNOT_BE_SENT_BECAUSE_R_NTHE_RECIPIENTS_INVENTORY_IS_FULL | The gift cannot be sent because\r\nthe recipient's Inventory is full. |
| 4541 | SP_4541_ONE_OF_THE_ITEMS_WILL_DISAPPEAR_R_NAFTER_ITS_EFFECTIVE_DATE_R_NWOULD_YOU_LIKE_TO | One of the items will disappear\r\nafter its effective date.\r\nWould you like to purchase it? |
| 4542 | SP_4542_S_MEDAL | 's Medal |
| 4543 | SP_4543_YOU_HAVE_LOST_THE_TITLE_SO_S_HAS_BEEN_GIVEN_TO_ANOTHER_CHARACTER | You have lost the title so %s has been given to another character. |
| 4544 | SP_4544_UI_UIWINDOWIMG_MURUENGRAID_PLAYER | UI/UIWindow.img/muruengRaid/player |
| 4545 | SP_4545_UI_UIWINDOWIMG_MURUENGRAID_MONSTER | UI/UIWindow.img/muruengRaid/monster |
| 4546 | SP_4546_UI_UIWINDOWIMG_MURUENGRAID_CLOCK | UI/UIWindow.img/muruengRaid/clock |
| 4547 | SP_4547_UI_UIWINDOWIMG_MURUENGRAID_ENERGY_EMPTY | UI/UIWindow.img/muruengRaid/energy/empty |
| 4548 | SP_4548_UI_UIWINDOWIMG_MURUENGRAID_ENERGY_FULL | UI/UIWindow.img/muruengRaid/energy/full |
| 4549 | SP_4549_UI_UIWINDOWIMG_MURUENGRAID_PLAYER_GAGE | UI/UIWindow.img/muruengRaid/player/Gage |
| 4550 | SP_4550_UI_UIWINDOWIMG_MURUENGRAID_ENERGY_EMPTY_GAGE | UI/UIWindow.img/muruengRaid/energy/empty/Gage |
| 4551 | SP_4551_UI_UIWINDOWIMG_MURUENGRAID_NUMBER | UI/UIWindow.img/muruengRaid/number |
| 4552 | SP_4552_UI_UIWINDOWIMG_MURUENGRAID_NUMBER_BAR | UI/UIWindow.img/muruengRaid/number/bar |
| 4553 | SP_4553_ENERGY | energy |
| 4554 | SP_4554_UI_UIWINDOWIMG_CLASSMATCH_BACKGRND | UI/UIWindow.img/classMatch/backgrnd |
| 4555 | SP_4555_UI_UIWINDOWIMG_CLASSMATCH_LOADING | UI/UIWindow.img/classMatch/Loading |
| 4556 | SP_4556_HTTP__SNEXONCOM_MAPLESTORY_PAGE_GNXGAMEASPXURLEVENT_CLASSBATTLE_GAMEVIEWKEYS | http://%s.nexon.com/maplestory/page/Gnxgame.aspx?URL=Event/classbattle/gameview&key=%s |
| 4557 | SP_4557_UI_UIWINDOWIMG_CLASSMATCH_BTOK | UI/UIWindow.img/classMatch/BtOK |
| 4558 | SP_4558_YOU_CANNOT_DROP_THE_ITEM_HERE | You cannot drop the item here. |
| 4559 | SP_4559_THIS_ITEM_CANNOT_BE_DISASSEMBLED | This item cannot be disassembled. |
| 4560 | SP_4560_MAP_EFFECTIMG_MIRO_RR1_D | Map/Effect.img/miro/RR1/%d |
| 4561 | SP_4561_MAP_EFFECTIMG_MIRO_RR2_D | Map/Effect.img/miro/RR2/%d |
| 4562 | SP_4562_MAP_EFFECTIMG_MIRO_RR3_D | Map/Effect.img/miro/RR3/%d |
| 4563 | SP_4563_WHEN_USED_ON_S__THE_EFFECTIVE_DATE_WILL_BE_EXTENDED_BY_S_WOULD_YOU_LIKE_TO_USE_I | When used on %s,  the effective date will be extended by %s. Would you like to use it? |
| 4564 | SP_4564_YOU_CANNOT_EXTEND_THE_EFFECTIVE_DATE_BEYOND_D_DAYS | You cannot extend the effective date beyond %d days. |
| 4565 | SP_4565_D_DAYS | %d days |
| 4566 | SP_4566_D_HRS | %d hrs. |
| 4567 | SP_4567_D_MIN | %d min. |
| 4568 | SP_4568_YOU_COULD_NOT_ACQUIRE_THE_EFFECT | You could not acquire the effect. |
| 4569 | SP_4569_FOR_EQUIPPING_S_ADDITIONAL_D_BONUS_EXP_WILL_BE_REWARDED_WHEN_YOU_HUNT_MONSTERS | For equipping %s, additional %d%% bonus EXP will be rewarded when you hunt monsters. |
| 4570 | SP_4570_AFTER_D_HRS_OF_EQUIPPING_S_ADDITIONAL_D_BONUS_EXP_WILL_BE_REWARDED__WHEN_YOU_HUN | After %d hrs. of equipping %s, additional %d%% bonus EXP will be rewarded  when you hunt monsters. |
| 4571 | SP_4571_UI_UIWINDOWIMG_FAMILY_BTOK | UI/UIWindow.img/Family/BtOK |
| 4572 | SP_4572_UI_UIWINDOWIMG_FAMILY_BTTREE | UI/UIWindow.img/Family/BtTree |
| 4573 | SP_4573_UI_UIWINDOWIMG_FAMILY_BTLEFT | UI/UIWindow.img/Family/BtLeft |
| 4574 | SP_4574_UI_UIWINDOWIMG_FAMILY_BTRIGHT | UI/UIWindow.img/Family/BtRight |
| 4575 | SP_4575_UI_UIWINDOWIMG_FAMILY_BTFAMILYPRECEPT | UI/UIWindow.img/Family/BtFamilyPrecept |
| 4576 | SP_4576_UI_UIWINDOWIMG_FAMILY_BTSPECIAL | UI/UIWindow.img/Family/BtSpecial |
| 4577 | SP_4577_UI_UIWINDOWIMG_FAMILY_BTJUNIORENTRY | UI/UIWindow.img/Family/BtJuniorEntry |
| 4578 | SP_4578_UI_UIWINDOWIMG_FAMILY_RIGHTICON_D | UI/UIWindow.img/Family/RightIcon/%d |
| 4579 | SP_4579_UI_UIWINDOWIMG_FAMILYTREE_PLATELEADER_D | UI/UIWindow.img/FamilyTree/PlateLeader/%d |
| 4580 | SP_4580_UI_UIWINDOWIMG_FAMILYTREE_PLATEOTHERS_D | UI/UIWindow.img/FamilyTree/PlateOthers/%d |
| 4581 | SP_4581_UI_UIWINDOWIMG_FAMILYTREE_BTJUNIORENTRY | UI/UIWindow.img/FamilyTree/BtJuniorEntry |
| 4582 | SP_4582_UI_UIWINDOWIMG_FAMILYTREE_BTBYE | UI/UIWindow.img/FamilyTree/BtBye |
| 4583 | SP_4583_UI_UIWINDOWIMG_FAMILYTREE_SELECTED | UI/UIWindow.img/FamilyTree/selected |
| 4584 | SP_4584_FIELD | field |
| 4585 | SP_4585_ENTER_THE_NAME_OF_THE_CHARACTER_YOU_WISH_TO_ADD_AS_JUNIOR_YOU_MUST_BE_IN_THE_SAM | Enter the name of the character you wish to add as Junior. You must be in the same channel as well as map to add. |
| 4586 | SP_4586_ENTER_THE_NAME_OF_THE_CHARACTER_YOU_WISH_TO_DELETE | Enter the name of the character you wish to delete. |
| 4587 | SP_4587_ENTER_THE_FAMILY_PRECEPTS | Enter the family precepts. |
| 4588 | SP_4588_YOU_DO_NOT_HAVE_FAMILY_MEMBERS | (You do not have family members.) |
| 4589 | SP_4589_YOU_DO_NOT_HAVE_A_LEADER | (You do not have a Leader.) |
| 4590 | SP_4590_YOU_DO_NOT_HAVE_AN_SENIOR | (You do not have an Senior.) |
| 4591 | SP_4591_YOU_DO_NOT_HAVE_A_FAMILY_YET | (You do not have a family yet.) |
| 4592 | SP_4592__PLEASE_ADD_A_JUNIOR_ | [Please add a Junior.] |
| 4593 | SP_4593_S_FAMILY | %s Family |
| 4594 | SP_4594_SENIORD_PPL | Senior(%d ppl.) |
| 4595 | SP_4595_JUNIORD_PPL | Junior(%d ppl.) |
| 4596 | SP_4596_THE_NAME_YOU_ENTERED_IS_INCORRECT_OR_HE_SHE_IS_CURRENTLY_NOT_LOGGED_IN | The name you entered is incorrect or he/she is currently not logged in. |
| 4597 | SP_4597_YOU_CANNOT_ADD_THIS_CHARACTER_AS_A_JUNIOR | You cannot add this character as a Junior. |
| 4598 | SP_4598_YOU_BELONG_TO_THE_SAME_FAMILY | You belong to the same family. |
| 4599 | SP_4599_YOU_DO_NOT_BELONG_TO_THE_SAME_FAMILY | You do not belong to the same family. |
| 4600 | SP_4600_THE_CHARACTER_YOU_HAVE_ENTERED_DOES_NOT_EXIST | The character you have entered does not exist. |
| 4601 | SP_4601_THE_CHARACTER_YOU_WISH_TO_ADD_AS_R_NA_JUNIOR_MUST_BE_IN_THE_SAME_MAP | The character you wish to add as\r\na Junior must be in the same map. |
| 4602 | SP_4602_THIS_CHARACTER_IS_ALREADY_A_JUNIOR_OF_ANOTHER_CHARACTER | This character is already a Junior of another character. |
| 4603 | SP_4603_THE_JUNIOR_YOU_WISH_TO_ADD_R_NMUST_BE_AT_A_LOWER_RANK | The Junior you wish to add\r\nmust be at a lower rank. |
| 4604 | SP_4604_THE_GAP_BETWEEN_YOU_AND_YOUR_R_NJUNIOR_MUST_BE_WITHIN_20_LEVELS | The gap between you and your\r\njunior must be within 20 levels. |
| 4605 | SP_4605_THE_JUNIOR_YOU_WISH_TO_ADD_R_NMUST_BE_OVER_LEVEL_10 | The Junior you wish to add\r\nmust be over Level 10. |
| 4606 | SP_4606_ANOTHER_CHARACTER_HAS_REQUESTED_TO_ADD_THIS_CHARACTER_R_NPLEASE_TRY_AGAIN_LATER | Another character has requested to add this character.\r\nPlease try again later. |
| 4607 | SP_4607_ANOTHER_CHARACTER_HAS_REQUESTED_A_SUMMON_R_NPLEASE_TRY_AGAIN_LATER | Another character has requested a summon.\r\nPlease try again later. |
| 4608 | SP_4608_YOU_CAN_ONLY_USE_IT_WHEN_6_JUNIOR_FAMILY_MEMBERS_WHOSE_NAMES_CAN_BE_CONFIRMED_ON | You can only use it when 6 Junior family members whose names can be confirmed on the Family Tree are logged in. |
| 4609 | SP_4609_THE_SUMMONS_HAS_FAILED_YOUR_CURRENT_LOCATION_OR_STATE_DOES_NOT_ALLOW_A_SUMMONS | The summons has failed. Your current location or state does not allow a summons. |
| 4610 | SP_4610_THE_FAMILY_CANNOT_EXTEND_MORE_THAN_1000_GENERATIONS_FROM_ABOVE_AND_BELOW | The family cannot extend more than 1000 generations from above and below. |
| 4611 | SP_4611_YOU_DO_NOT_HAVE_ROOM_TO_ADD__AS_A_JUNIOR | You do not have room to add % as a Junior. |
| 4612 | SP_4612_S_YOU_CANNOT_ADD_ANY_MORE_JUNIORS | %s, you cannot add any more Juniors. |
| 4613 | SP_4613_S_WOULD_LIKE_TO_R_NBE_YOUR_SENIOR_WOULD_YOU_LIKE_TO_BE_R_NSS_JUNIOR | %s would like to\r\nbe your Senior. Would you like to be\r\n%s's Junior? |
| 4614 | SP_4614_S_HAS_DECLINED_YOUR_JUNIOR_REQUEST | %s has declined your Junior request. |
| 4615 | SP_4615_YOU_HAVE_ADDED_S_AS_A_JUNIOR_PLEASE_DO_YOUR_BEST_TO_GUIDE_THE_JUNIOR_IN_HIS_HER_ | You have added %s as a Junior. Please do your best to guide the Junior in his/her game play. |
| 4616 | SP_4616_S_HAS_BECOME_YOUR_SENIOR_HE_SHE_WILL_GUIDE_YOU_IN_YOUR_GAME_PLAY | %s has become your Senior. He/she will guide you in your game play. |
| 4617 | SP_4617_S_HAS_REQUESTED_TO_SEVER_TIES_SO_YOUR_FAMILY_RELATIONSHIP_HAS_ENDED | %s has requested to sever ties, so your family relationship has ended. |
| 4618 | SP_4618_YOU_HAVE_SEVERED_TIES_WITH_S_R_NYOUR_FAMILY_RELATIONSHIP_HAS_ENDED | You have severed ties with %s.\r\nYour family relationship has ended. |
| 4619 | SP_4619_YOUR_REP_HAS_INCREASED_S_D | Your Rep has increased (%s, +%d) |
| 4620 | SP_4620_YOU_HAVE_USED_D_REP_POINTS | You have used %d Rep Points. |
| 4621 | SP_4621_S_HAS_USED_S_ENTITLEMENT | %s has used '%s' Entitlement. |
| 4622 | SP_4622_IF_YOU_CHOOSE_TO_SEVER_TIES_WITH_THE_SELECTED_CHARACTER_EVERYONE_IN_YOUR_FAMILY_ | If you choose to sever ties with the selected character, everyone in your family will be affected. Would you like to proceed? |
| 4623 | SP_4623_PLEASE_ENTER_THE_NAME_OF_THE_TARGETED_FAMILY_MEMBER | Please enter the name of the targeted family member. |
| 4624 | SP_4624_FROM_S_S_HAS_REQUESTED_A_SUMMONS_WOULD_YOU_LIKE_TO_TRANSPORT | From %s, %s has requested a summons. Would you like to transport? |
| 4625 | SP_4625_S_HAS_DECLINED_THE_SUMMONS_REQUEST | %s has declined the summons request. |
| 4626 | SP_4626_S_YOU_HAVE_EXCEED_YOUR_ENTITLEMENT_LIMIT | %s, you have exceed your Entitlement limit. |
| 4627 | SP_4627_REPS_ARE_MARKED_AS__CURRENT_REP_TOTAL_REP__R_NUSE_UP_YOUR_CURRENT_REPS_TO_ACQUIR | Reps are marked as [Current Rep/Total Rep].\r\nUse up your current Reps to acquire [Entitlements]. |
| 4628 | SP_4628_JUNIORS_ARE_MARKED_AS__CURRENT_JUNIORS_MAX_JUNIORS_AVAILABLE__R_N_WHEN_A_JUNIOR_ | Juniors are marked as [Current Juniors/Max Juniors Available].\r\n When a Junior you have registered under gains EXP or levels up, your Rep will increase as well.\r\nHelp your Junior level up to further increase your Rep. |
| 4629 | SP_4629_IF_THE_JUNIOR_SLOT_IS_EMPTY_THEN_YOU_CAN_ADD_A_JUNIOR_THAT_WILL_HELP_ENHANCE_YOU | If the Junior slot is empty, then you can add a Junior that will help enhance your Rep. |
| 4630 | SP_4630_YOUR_SENIOR_WILL_SEVER_TIES_AND_START_A_NEW_FAMILY_R_NTHE_MEMBERS_BELOW_YOU_WILL | Your Senior will sever ties and start a new family.\r\nThe members below you will belong to your family. |
| 4631 | SP_4631_THIS_WILL_END_YOUR_RELATIONSHIP_WITH_YOUR_JUNIOR_R_NTHE_JUNIOR_AND_EVERYONE_UNDE | This will end your relationship with your Junior.\r\nThe Junior and everyone underneath will all be removed from the Family. |
| 4632 | SP_4632_THIS_COVERS_EVERYONE_IN_THE_FAMILY_R_NTHE_NUMBER_MAY_BE_HIGHER_THAN_THE_ONE_SHOW | This covers everyone in the Family.\r\nThe number may be higher than the one shown in the Family Tree. |
| 4633 | SP_4633_ENTITLEMENT_USED_TODAY_D_R_NENTITLEMENT_AVAILABLE_TODAY_D | Entitlement used today: %d\r\nEntitlement available today: %d |
| 4634 | SP_4634_YOU_CANNOT_USE_ANY_MORE_ENTITLEMENTS_TODAY | You cannot use any more Entitlements today. |
| 4635 | SP_4635_C_YOU_HAVE_SURPASSED_YOUR_SENIORS_LEVEL_SO_YOUR_CUMULATIVE_REP_R_NCWILL_BE_REDUC | #c* You have surpassed your Senior's level, so your cumulative Rep#\r\n#cwill be reduced in half. Please help your Senior level up!#\r\n |
| 4636 | SP_4636_C_YOUR_JUNIOR_HAS_SURPASSED_YOUR_LEVEL_SO_YOUR_CUMULATIVE_REP_R_NCWILL_BE_REDUCE | #c* Your Junior has surpassed your level, so your cumulative Rep#\r\n#cwill be reduced in half. Please try harder to level up!#\r\n |
| 4637 | SP_4637_REPS_RECORDED_TO_YOUR_SENIOR__S_R_N | Reps recorded to your Senior : %s\r\n |
| 4638 | SP_4638_ENTITLEMENT_IN_USE__R_N | Entitlement in use :\r\n |
| 4639 | SP_4639_ENTITLEMENT_USED_TODAY__R_N | Entitlement used today :\r\n |
| 4640 | SP_4640_S___D_TIMES_R_N | \t%s - %d times\r\n |
| 4641 | SP_4641_REPS_RECORDED_TODAY__S_R_N | Reps recorded today : %s\r\n |
| 4642 | SP_4642_TIME_ELAPSED_SINCE_LOGIN__DHRS_DMIN_R_N | Time elapsed since login : %dhrs. %dmin.\r\n |
| 4643 | SP_4643_CURRENT_LOCATION__S_R_N | Current Location : %s\r\n |
| 4644 | SP_4644_CURRENT_REPS__S_R_NTOTAL_REPS__S_R_N | Current Reps : %s\r\nTotal Reps : %s\r\n |
| 4645 | SP_4645__R_N | ______________________________________________\r\n |
| 4646 | SP_4646_ISNT_THERE_ANYTHING_ELSE_YOU_WANT_ILL_MAKE_A_NOTE_OF_IT | Isn't there anything else you want? I'll make a note of it. |
| 4647 | SP_4647_IM_NOT_DOING_BUSINESS_AT_THIS_TIME_DONT_I_DESERVE_SOME_REST_TOO | I'm not doing business at this time. Don't I deserve some rest, too? |
| 4648 | SP_4648_THATS_NOT_SOMETHING_I_BARTER_ILL_SHOW_YOU_THE_LIST_AGAIN_FOR_YOU_TO_CHOOSE_FROM | That's not something I barter. I'll show you the list again for you to choose from. |
| 4649 | SP_4649_ARE_YOU_BEGGING | Are you begging? |
| 4650 | SP_4650_WHY_DO_YOU_CARRY_AROUND_SO_MUCH_MESOS_COME_BACK_AFTER_EMPTYING_YOUR_POCKETS | Why do you carry around so much Mesos? Come back after emptying your pockets. |
| 4651 | SP_4651_TAKING_YOUR_PRECIOUS_TIME_HUH_I_DONT_BARTER_THIS_ITEM_ANYMORE | Taking your precious time, huh? I don't barter this item anymore. |
| 4652 | SP_4652_IM_DONE_BARTERING_THIS_ITEM_FOR_THE_DAY_COME_BACK_TOMORROW | I'm done bartering this item for the day. Come back tomorrow. |
| 4653 | SP_4653_WHY_ARE_YOU_OBSESSING_OVER_THIS_ITEM_I_WONT_BARTER_ANYMORE | Why are you obsessing over this item? I won't barter anymore. |
| 4654 | SP_4654_YOURE_GREEDY_HOW_ABOUT_YOU_LOWER_THE_QUANTITY | You're greedy. How about you lower the quantity? |
| 4655 | SP_4655_IM_A_LITTLE_PREOCCUPIED_RIGHT_NOW_SO_COME_BACK_LATER | I'm a little preoccupied right now, so come back later. |
| 4656 | SP_4656_YOU_DO_NOT_HAVE_THE_ITEM | You do not have the item. |
| 4657 | SP_4657_UI_UIWINDOWIMG_KARMASCISSORS_BACKGRND | UI/UIWindow.img/KarmaScissors/backgrnd |
| 4658 | SP_4658_ONCE_DROPPED_THE_ITEM_CANNOT_BE_TRADED_R_NWOULD_YOU_STILL_LIKE_TO_DROP_IT | Once dropped, the item cannot be traded.\r\nWould you still like to drop it? |
| 4659 | SP_4659_ONCE_PURCHASED_THE_ITEM_CANNOT_BE_TRADED_R_NWOULD_YOU_STILL_LIKE_TO_PURCHASE_IT | Once purchased, the item cannot be traded.\r\nWould you still like to purchase it? |
| 4660 | SP_4660_ONCE_STORED_IN_THE_INVENTORY_R_NTHE_ITEM_CANNOT_BE_TRADED_R_NBY_THE_CHARACTER_WH | Once stored in the Inventory,\r\nthe item cannot be traded\r\nby the character when taken out.\r\nWould you still like to store it? |
| 4661 | SP_4661_ONCE_FOUND_THE_ITEM_CANNOT_BE_TRADED_R_NWOULD_YOU_STILL_LIKE_TO_FIND_IT | Once found, the item cannot be traded.\r\nWould you still like to find it? |
| 4662 | SP_4662_SOME_ITEMS_YOU_ARE_TRYING_TO_BARTER_R_NCANNOT_BE_TRADED_ONCE_RECEIVED_R_NWOULD_Y | Some items you are trying to barter\r\ncannot be traded once received.\r\nWould you still like to proceed? |
| 4663 | SP_4663_THIS_ITEM_HAS_ALREADY_BEEN_UNLOCKED | This item has already been unlocked. |
| 4664 | SP_4664_YOU_HAVE_USED_THE_SCISSORS_OF_KARMA_SO_1_TIME_OF_TRADING_HAS_BEEN_ENABLED | You have used the Scissors of Karma, so 1 time of trading has been enabled. |
| 4665 | SP_4665_CYOU_CAN_ENABLE_1_TIME_OF_TRADING_WHEN_YOU_USE_THE_SCISSORS_OF_KARMA | #cYou can enable 1 time of trading when you use the Scissors of Karma.# |
| 4666 | SP_4666_YOU_CURRENTLY_DO_NOT_HAVE_ANY_ITEMS_THAT_R_NCAN_USE_THE_SCISSORS_OF_KARMA_R_NWOU | You currently do not have any items that\r\ncan use the Scissors of Karma.\r\nWould you still like to purchase it? |
| 4667 | SP_4667__R_N_CITEMS_THAT_CAN_BE_USED_ | \r\n-#cItems that can be used#- |
| 4668 | SP_4668_YOU_HAVE_SUCCESSFULLY_CREATED_A_KNIGHT_CHARACTER_R_NYOU_CAN_SELECT_A_KNIGHT_CHAR | You have successfully created a Knight character.\r\nYou can select a Knight character\r\nwhen you re-login. |
| 4669 | SP_4669_THE_KNIGHT_CHARACTER_HAS_NOT_BEEN_CREATED_DUE_TO_AN_UNKNOWN_ERROR | The Knight character has not been created due to an unknown error. |
| 4670 | SP_4670_THIS_NAME_ALREADY_EXISTS | This name already exists. |
| 4671 | SP_4671_ALL_OF_YOUR_SLOTS_HAVE_BEEN_FILLED_PLEASE_PURCHASE_A_CHARACTER_SLOT_AT_THE_CASH_ | All of your slots have been filled. Please purchase a Character Slot at the Cash Shop. |
| 4672 | SP_4672_THIS_NAME_CANNOT_BE_USED | This name cannot be used. |
| 4673 | SP_4673_THE_RANKING_INFORMATION_CAN_BE_REFERENCED_WHEN_YOUR_R_NCHARACTER_CHANGES_OCCUPAT | The Ranking Information can be referenced when your\r\ncharacter changes occupation or reaches above Lv. 10. |
| 4674 | SP_4674_UI_BASICIMG_SHOWLEVEL_D | UI/Basic.img/ShowLevel/%d |
| 4675 | SP_4675_UI_BASICIMG_SHOWLEVEL_D_LEFT | UI/Basic.img/ShowLevel/%d/left |
| 4676 | SP_4676_UI_BASICIMG_SHOWLEVEL_BRACKET_RIGHT | UI/Basic.img/ShowLevel/Bracket/right |
| 4677 | SP_4677_S_S_HAS_REACHED_LV_D | <%s> %s has reached Lv. %d. |
| 4678 | SP_4678___THE_REPS_YOU_HAVE_RECEIVED_FROM_S_WILL_BE_REDUCED_IN_HALF | - The Reps you have received from %s will be reduced in half. |
| 4679 | SP_4679_UI_UIWINDOWIMG_EQUIP_BTPETEQUIPSHOW | UI/UIWindow.img/Equip/BtPetEquipShow |
| 4680 | SP_4680_UI_UIWINDOWIMG_EQUIP_BTPETEQUIPHIDE | UI/UIWindow.img/Equip/BtPetEquipHide |
| 4681 | SP_4681_UI_UIWINDOWIMG_USERINFO_BTPETD | UI/UIWindow.img/UserInfo/BtPet%d |
| 4682 | SP_4682_UI_UIWINDOWIMG_USERINFO_BOSSPETCROWN | UI/UIWindow.img/UserInfo/bossPetCrown |
| 4683 | SP_4683_S_SOUND1 | %s/sound1 |
| 4684 | SP_4684_UI_ITCIMG_BUY_BTREGISTRATION | UI/ITC.img/Buy/BtRegistration |
| 4685 | SP_4685_UI_ITCIMG_MYPAGE_BACKGRND1 | UI/ITC.img/MyPage/backgrnd1 |
| 4686 | SP_4686_ALL | ALL |
| 4687 | SP_4687_UI_ITCIMG_SORT_NORMALASC_D | UI/ITC.img/Sort/Normal_Asc/%d |
| 4688 | SP_4688_UI_ITCIMG_SORT_PRESSEDASC_D | UI/ITC.img/Sort/Pressed_Asc/%d |
| 4689 | SP_4689_UI_ITCIMG_SORT_NORMALDESC_D | UI/ITC.img/Sort/Normal_Desc/%d |
| 4690 | SP_4690_UI_ITCIMG_SORT_PRESSEDDESC_D | UI/ITC.img/Sort/Pressed_Desc/%d |
| 4691 | SP_4691__D | /%d |
| 4692 | SP_4692_UI_NPTGUIDELINEIMG_NPTGUIDE | UI/Npt_GuideLine.img/NptGuide |
| 4693 | SP_4693_ETC_NPTEXCEPTIONIMG | Etc/NPT_exception.img |
| 4694 | SP_4694_STRING_TOOLTIPHELPIMG_NPT_BUTTON_SELLINVENTORY | String/ToolTipHelp.img/NPT/Button/SellInventory |
| 4695 | SP_4695_10 | 10 |
| 4696 | SP_4696_100 | 100 |
| 4697 | SP_4697_500 | 500 |
| 4698 | SP_4698_1000 | 1000 |
| 4699 | SP_4699_UI_ITCIMG_AUCTION_BACKGRND | UI/ITC.img/Auction/backgrnd |
| 4700 | SP_4700_UI_ITCIMG_BTSELL | UI/ITC.img/BtSell |
| 4701 | SP_4701_UI_ITCIMG_BTAUCTION | UI/ITC.img/BtAuction |
| 4702 | SP_4702_UI_ITCIMG_AUCTION_BTBID | UI/ITC.img/Auction/BtBid |
| 4703 | SP_4703_UI_ITCIMG_AUCTION_BACKGRND1 | UI/ITC.img/Auction/backgrnd1 |
| 4704 | SP_4704_STRING_TOOLTIPHELPIMG_NPT_BUTTON_SELLAUCTION | String/ToolTipHelp.img/NPT/Button/SellAuction |
| 4705 | SP_4705_UI_ITCIMG_BUY_ITEM_BACKGRND | UI/ITC.img/Buy/Item/backgrnd |
| 4706 | SP_4706_UI_ITCIMG_BASE_TAB2_ENABLE | UI/ITC.img/Base/Tab2/Enable |
| 4707 | SP_4707_UI_ITCIMG_BASE_TAB2_DISABLE | UI/ITC.img/Base/Tab2/Disable |
| 4708 | SP_4708_UI_ITCIMG_MYPAGE_BACKGRND | UI/ITC.img/MyPage/backgrnd |
| 4709 | SP_4709_UI_ITCIMG_SELL_BACKGRND | UI/ITC.img/Sell/backgrnd |
| 4710 | SP_4710_UI_ITCIMG_SELL_BTBUY | UI/ITC.img/Sell/BtBuy |
| 4711 | SP_4711_UI_ITCIMG_SELL_BTSHOPPINGBASKET | UI/ITC.img/Sell/BtShoppingBasket |
| 4712 | SP_4712_UI_ITCIMG_BUY_BTSELL | UI/ITC.img/Buy/BtSell |
| 4713 | SP_4713_UI_ITCIMG_MYPAGE_BTBUY | UI/ITC.img/MyPage/BtBuy |
| 4714 | SP_4714_UI_ITCIMG_MYPAGE_BTDELETE | UI/ITC.img/MyPage/BtDelete |
| 4715 | SP_4715_UI_ITCIMG_MYPAGE_BTCANCEL | UI/ITC.img/MyPage/BtCancel |
| 4716 | SP_4716_D_D_02D | %d-%d-%02d |
| 4717 | SP_4717_UI_ITCIMG_BASE_BACKGRND | UI/ITC.img/Base/backgrnd |
| 4718 | SP_4718_UI_ITCIMG_BTSEARCH | UI/ITC.img/BtSearch |
| 4719 | SP_4719_UI_ITCIMG_SELL_BACKGRND1 | UI/ITC.img/Sell/backgrnd1 |
| 4720 | SP_4720_UI_ITCIMG_BUY_BACKGRND1 | UI/ITC.img/Buy/backgrnd1 |
| 4721 | SP_4721_UI_ITCIMG_BUY_BACKGRND3 | UI/ITC.img/Buy/backgrnd3 |
| 4722 | SP_4722_UI_ITCIMG_BASE_TAB_ENABLE | UI/ITC.img/Base/Tab/Enable |
| 4723 | SP_4723_UI_ITCIMG_BASE_TAB_DISABLE | UI/ITC.img/Base/Tab/Disable |
| 4724 | SP_4724_UI_ITCIMG_BASE_PREVIEW_0 | UI/ITC.img/Base/Preview/0 |
| 4725 | SP_4725_UI_ITCIMG_SELL_BTCANCEL | UI/ITC.img/Sell/BtCancel |
| 4726 | SP_4726_UI_ITCIMG_SELL_BTREGISTRATION | UI/ITC.img/Sell/BtRegistration |
| 4727 | SP_4727_UI_ITCIMG_TAB_D | UI/ITC.img/Tab/%d |
| 4728 | SP_4728_EQP | EQP |
| 4729 | SP_4729_USE | USE |
| 4730 | SP_4730_SET_UP | SET UP |
| 4731 | SP_4731_ETC | ETC |
| 4732 | SP_4732_UI_ITCIMG_SELL_TAB_D | UI/ITC.img/Sell/Tab/%d |
| 4733 | SP_4733_UI_ITCIMG_MYPAGE_TAB_D | UI/ITC.img/MyPage/Tab/%d |
| 4734 | SP_4734_UI_ITCIMG_PICTUREPLATE_HOWTO | UI/ITC.img/PicturePlate/HowTo |
| 4735 | SP_4735_UI_ITCIMG_PICTUREPLATE_HOWTO1 | UI/ITC.img/PicturePlate/HowTo1 |
| 4736 | SP_4736_UI_ITCIMG_BTCHARGE | UI/ITC.img/BtCharge |
| 4737 | SP_4737_UI_ITCIMG_BTCHECK | UI/ITC.img/BtCheck |
| 4738 | SP_4738_UI_ITCPREVIEWIMG | UI/ITCPreview.img |
| 4739 | SP_4739_UI_ITCIMG_PICTUREPLATE_NOITEM | UI/ITC.img/PicturePlate/NoItem |
| 4740 | SP_4740_THE_ITEM_TRADING_SYSTEM_IS_CURRENTLY_UNAVAILABLE_PLEASE_TRY_AGAIN_LATER | The Item-Trading System is currently unavailable. Please try again later. |
| 4741 | SP_4741_YOU_DO_NOT_HAVE_ANY_ITEM_REGISTERED | You do not have any item registered. |
| 4742 | SP_4742_NOT_ENOUGH_NUMBER_OF_ITEMS_R_NPLEASE_DOUBLE_CHECK | Not enough number of items.\r\nPlease double-check. |
| 4743 | SP_4743_THE_ITEM_YOU_HAVE_REGISTERED_R_NCANNOT_BE_IN_TRANSACTION | The item you have registered\r\ncannot be in transaction. |
| 4744 | SP_4744_THE_ITEM_TRADING_SHOP_IS_CURRENTLY_UNAVAILABLE_PLEASE_TRY_AGAIN_LATER | The Item-Trading Shop is currently unavailable. Please try again later. |
| 4745 | SP_4745_THE_MAPLESTORY_TRADING_SYSTEM_IS_NOT_AVAILABLE_IN_THE_TRIAL_VERSION | The MapleStory Trading System is not available in the Trial Version. |
| 4746 | SP_4746_READY_TO_BECOME_A_MAPLE_TRADER_DOWNLOAD_THE_FULL_CLIENT | Ready to become a Maple Trader? Download the full client! |
| 4747 | SP_4747_THE_FUNCTION_IS_CURRENTLY_UNAVAILABLE | The function is currently unavailable. |
| 4748 | SP_4748_ALERTING_THE_SELLER_OF_THE_PRICE_AFTER_DEDUCTING_THE_5_TRANSACTION_FEE | Alerting the seller of the price after deducting the 5% transaction fee. |
| 4749 | SP_4749_WOULD_YOU_LIKE_TO_MAKE_A_DIRECT_PURCHASE_R_NOF_THIS_ITEM_FOR_D | Would you like to make a direct purchase\r\nof this item for %d? |
| 4750 | SP_4750_SOLD | Sold |
| 4751 | SP_4751_PURCHASED | Purchased |
| 4752 | SP_4752_BID_LOST | Bid Lost |
| 4753 | SP_4753_CANCELLED | Cancelled |
| 4754 | SP_4754_EXHIBIT | Exhibit |
| 4755 | SP_4755_BID | Bid |
| 4756 | SP_4756_CURRENT_TOP_BID__D | Current Top Bid : %d |
| 4757 | SP_4757_TIME_REMAINING__D_HR_D_MIN | Time Remaining : %d hr %d min |
| 4758 | SP_4758_BID_RANGE__D | Bid Range : %d |
| 4759 | SP_4759_BUY_NOW__D | Buy-Now : %d |
| 4760 | SP_4760_YOU_CANNOT_MAKE_A_CONSECUTIVE_BID | You cannot make a consecutive bid. |
| 4761 | SP_4761_S_OF_S_R_NIS_SOLD_FOR_D_NX | %s of %s\r\nis sold for %d NX. |
| 4762 | SP_4762_S_OF_S_R_NIS_BOUGHT_FOR_D_NX | %s of %s\r\nis bought for %d NX. |
| 4763 | SP_4763_PRICE_PER_UNIT__D | Price per unit : %d |
| 4764 | SP_4764_D_HR_D_MIN | %d hr %d min |
| 4765 | SP_4765_THE_BUY_NOW_PRICE_IS_LOWER_R_NTHAN_THE_STARTING_BID | The Buy-Now price is lower\r\nthan the starting bid. |
| 4766 | SP_4766_BID_COUNT__D | Bid Count : %d |
| 4767 | SP_4767_ITEM_COUNT__D | Item Count : %d |
| 4768 | SP_4768_UNABLE_TO_PULL_OUT_OF_THE_SALE_R_NYOU_HAVE_AT_LEAST_1_BID_ON_THE_ITEM | Unable to pull out of the sale.\r\nYou have at least 1 bid on the item. |
| 4769 | SP_4769_THE_AUCTION_CAN_BE_SET_UP_24168_HRS_R_NPRIOR_TO_THE_AUCTION_IN_1_HR_INCREMENT | The auction can be set up 24~168 hrs\r\nprior to the auction in 1 hr increment. |
| 4770 | SP_4770_THIS_IS_CURRENTLY_UNDER_CONSTRUCTION | This is currently under construction. |
| 4771 | SP_4771_FOR_SALE | For Sale |
| 4772 | SP_4772_WANTED | Wanted |
| 4773 | SP_4773_AUCTION | Auction |
| 4774 | SP_4774_ALL | All |
| 4775 | SP_4775_CHARACTER | Character |
| 4776 | SP_4776_PLEASE_ENTER_A_NUMBER | Please enter a number |
| 4777 | SP_4777_ARE_YOU_SURE_YOU_WANT_TO_CANCEL_THE_SALE | Are you sure you want to cancel the sale? |
| 4778 | SP_4778_LIMIT_TO_D_UNITS_OR_LESS | Limit to %d units or less. |
| 4779 | SP_4779_SUCCESSFULLY_REGISTERED_THE_ITEM_FOR_SALE | Successfully registered the item for sale. |
| 4780 | SP_4780_ARE_YOU_SURE_YOU_WANT_TO_SELL_THIS_ITEM | Are you sure you want to sell this item? |
| 4781 | SP_4781_SUCCESSFULLY_REQUESTED_THE_ITEM_FOR_SALE | Successfully requested the item for sale. |
| 4782 | SP_4782_REQUESTING_THE_ITEM_FOR_SALE_FAILED | Requesting the item for sale failed. |
| 4783 | SP_4783_YOU_DO_NOT_HAVE_ENOUGH_NX | You do not have enough NX. |
| 4784 | SP_4784_AN_ERROR_HAS_OCCURRED_WHILE_USING_NX | An error has occurred while using NX. |
| 4785 | SP_4785_FAILED_TO_LOAD_THE_LIST | Failed to load the list. |
| 4786 | SP_4786_SUCCESSFULLY_CANCELED_THE_SALE | Successfully canceled the sale. |
| 4787 | SP_4787_THE_ITEM_HAS_BEEN_TRANSFERRED | The item has been transferred. |
| 4788 | SP_4788_THE_ITEM_HAS_BEEN_SUCCESSFULLY_PLACED_R_NIN_YOUR_CART | The item has been successfully placed\r\nin your Cart. |
| 4789 | SP_4789_FAILED_TO_PLACE_THE_ITEM_IN_YOUR_CART | Failed to place the item in your Cart. |
| 4790 | SP_4790_SUCCESSFULLY_DELETED_THE_ITEM_FROM_THE_CART | Successfully deleted the item from the Cart. |
| 4791 | SP_4791_FAILED_TO_REMOVE_THE_ITEM_FROM_THE_CART | Failed to remove the item from the Cart. |
| 4792 | SP_4792_NO_ITEM_IS_REGISTERED_FOR_SALE | No item is registered for sale. |
| 4793 | SP_4793_FAILED_TO_LOAD_THE_BUY_LIST | Failed to load the Buy List. |
| 4794 | SP_4794_SUCCESSFULLY_REQUESTED_TO_BUY_THE_ITEM | Successfully requested to buy the item. |
| 4795 | SP_4795_REQUEST_TO_BUY_THE_ITEM_HAS_FAILED | Request to buy the item has failed. |
| 4796 | SP_4796_THE_REQUEST_FOR_PURCHASE_HAS_BEEN_CANCELED | The request for purchase has been canceled. |
| 4797 | SP_4797_FAILED_TO_CANCEL_THE_REQUEST_FOR_PURCHASE | Failed to cancel the request for purchase. |
| 4798 | SP_4798_SUCCESSFULLY_REGISTERED_UNDER__BUY_ | Successfully registered under [Buy]. |
| 4799 | SP_4799_FAILED_TO_REGISTER_THE_ITEM_UNDER__BUY_ | Failed to register the item under [Buy]. |
| 4800 | SP_4800_7_DAYS | (7 days) |
| 4801 | SP_4801_PLEASE_ENTER_MORE_THAN_1_CHARACTER | Please enter more than 1 character. |
| 4802 | SP_4802_THE_SEARCH_YIELDED_0_RESULT | The search yielded 0 result. |
| 4803 | SP_4803_ARE_YOU_SURE_YOU_WANT_TO_BUY_THIS | Are you sure you want to buy this? |
| 4804 | SP_4804_LISTING_PRICE__D_NX | Listing Price : %d NX |
| 4805 | SP_4805_SALE_PRICE__D | Sale Price : %d |
| 4806 | SP_4806_THE_ITEM_IS_CANCELLED_OR_SOLD_OUT | The item is cancelled or sold out. |
| 4807 | SP_4807_THERE_ARE_D_ITEMS_IN_TRANSFER_INVENTORY_R_NONLY_D_ITEMS_CAN_BE_SHOWN_ON_THE_SCRE | There are %d items in Transfer Inventory.\r\nOnly %d items can be shown on the screen.\r\n Please move items in the Transfer Inventory\r\nto your Item Inventory. |
| 4808 | SP_4808_DUE_TO_AN_UNKNOWN_ERROR_R_NTHE_REQUEST_FOR_MTS_HAS_FAILED | Due to an unknown error,\r\nthe request for MTS has failed. |
| 4809 | SP_4809_YOU_CANNOT_GO_INTO_THE_TRADE_SHOP_DUE_TO_THE_LIMITATION_OF_USER_COUNT | You cannot go into the trade shop, due to the limitation of user count. |
| 4810 | SP_4810_THIS_ITEM_MAY_NOT_BE_SOLD | This Item may not be sold. |
| 4811 | SP_4811_YOU_DO_NOT_MEET_THE_MINIMUM_LEVEL_REQUIREMENT_TO_ACCESS_THE_TRADE_SHOP | You do not meet the minimum level requirement to access the Trade Shop. |
| 4812 | SP_4812_YOU_CANNOT_PLACE_A_BID_R_NON_YOUR_OWN_ITEM | You cannot place a bid\r\non your own item. |
| 4813 | SP_4813_YOU_CANNOT_USE_MINERVAS_OWL_IN_THIS_MAP | You cannot use Minerva's Owl in this map. |
| 4814 | SP_4814_THE_ITEM_HAS_BEEN_SOLD_R_NYOU_CANNOT_CANCEL_THE_SALE_OF_A_PURCHASED_ITEM | The item has been sold.\r\nYou cannot cancel the sale of a purchased item. |
| 4815 | SP_4815_THE_LISTING_PERIOD_ON__WANTED__ITEMS_R_NHAS_EXPIRED_R_ND_ITEMS_AND_D_NX_R_NHAVE_ | The Listing period on [WANTED] items\r\nhas expired.\r\n%d items and %d NX\r\nhave been returned to your MTS account |
| 4816 | SP_4816_THROWING_STARS_ARE_NOT_TRADABLE | Throwing Stars are not tradable. |
| 4817 | SP_4817_REPEATSTARTINDEX | repeatStartIndex |
| 4818 | SP_4818_EFFECT_SETEFFIMG_D_PETEFFECT | Effect/SetEff.img/%d/petEffect |
| 4819 | SP_4819_EFFECT_SETEFFIMG_D_PETBACKGRND_D | Effect/SetEff.img/%d/petBackgrnd/%d |
| 4820 | SP_4820_EFFECT_SETEFFIMG_D_PETBACKGRND | Effect/SetEff.img/%d/petBackgrnd |
| 4821 | SP_4821_YOUR_TRAINING_AS_A_BEGINNER_HAS_PEAKED_R_NYOU_MAY_NOW_SELECT_YOUR_1ST_JOB_ADVANC | Your training as a Beginner has peaked.\r\nYou may now select your 1st Job Advancement \r\nand become a Bowman, Thief, Magician or Warrior! \r\nTo continue the adventure, you have to download \r\nthe full client. \r\nDo you want to download full client now? \r\n(It's totally free to download the full client! |
| 4822 | SP_4822_IF_YOU_WISH_TO_DOWNLOAD_THE_FULL_CLIENT_LATER__R_NYOU_CAN_ALWAYS_VISIT_MAPLESTOR | If you wish to download the full client later, \r\nyou can always visit maplestory.nexon.net \r\nand download it! |
| 4823 | SP_4823_UI_UIWINDOWIMG_CANCELREQUESTS_ASKCONTINUECANCEL_BACKGRND | UI/UIWindow.img/CancelRequests/AskContinueCancel/backgrnd |
| 4824 | SP_4824_UI_UIWINDOWIMG_CANCELREQUESTS_ASKCONTINUECANCEL_BTYES | UI/UIWindow.img/CancelRequests/AskContinueCancel/BtYes |
| 4825 | SP_4825_UI_UIWINDOWIMG_CANCELREQUESTS_ASKCONTINUECANCEL_BTNO | UI/UIWindow.img/CancelRequests/AskContinueCancel/BtNo |
| 4826 | SP_4826_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELNAMECHANGE_BACKGRND | UI/UIWindow.img/CancelRequests/CancelNameChange/backgrnd |
| 4827 | SP_4827_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELNAMECHANGE_BTYES | UI/UIWindow.img/CancelRequests/CancelNameChange/BtYes |
| 4828 | SP_4828_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELNAMECHANGE_BTNO | UI/UIWindow.img/CancelRequests/CancelNameChange/BtNo |
| 4829 | SP_4829_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELNAMECHANGE_SUC | UI/UIWindow.img/CancelRequests/CancelNameChange/suc |
| 4830 | SP_4830_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELNAMECHANGE_FAIL | UI/UIWindow.img/CancelRequests/CancelNameChange/fail |
| 4831 | SP_4831_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELCHARACTERTRANSFER_BACKGRND | UI/UIWindow.img/CancelRequests/CancelCharacterTransfer/backgrnd |
| 4832 | SP_4832_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELCHARACTERTRANSFER_BTYES | UI/UIWindow.img/CancelRequests/CancelCharacterTransfer/BtYes |
| 4833 | SP_4833_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELCHARACTERTRANSFER_BTNO | UI/UIWindow.img/CancelRequests/CancelCharacterTransfer/BtNo |
| 4834 | SP_4834_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELCHARACTERTRANSFER_SUC | UI/UIWindow.img/CancelRequests/CancelCharacterTransfer/suc |
| 4835 | SP_4835_UI_UIWINDOWIMG_CANCELREQUESTS_CANCELCHARACTERTRANSFER_FAIL | UI/UIWindow.img/CancelRequests/CancelCharacterTransfer/fail |
| 4836 | SP_4836_PLEASE_DOWNLOAD_THE_FULL_CLIENT_TO_EXPERIENCE__R_NTHE_WORLD_OF_MAPLESTORY__R_NWO | Please download the full client to experience \r\nthe world of MapleStory. \r\nWould you like to download the full client\r\n from our website? |
| 4837 | SP_4837_YOU_MUST_BE_OVER_LEVEL_10_TO_SELL_R_NITEMS_ON_MAPLESTORY_TRADING_SYSTEM | You must be over level 10 to sell\r\nitems on MapleStory Trading System. |
| 4838 | SP_4838_YOUR_SCREEN_RESOLUTION_CANNOT_SUPPORT_WINDOWED_MODE_PLEASE_SELECT_A_HIGHER_SCREE | Your screen resolution cannot support windowed mode. Please select a higher screen resolution to use windowed mode. |
| 4839 | SP_4839_YOU_HAVE_BEEN_BLOCKED_BY_BPOLICE_S_FOR_THE_S_REASONK | You have been blocked by #bPolice %s for the %s reason.#k |
| 4840 | SP_4840_UI_CASHSHOPIMG_CSITEMSEARCH_BTSEARCH | UI/CashShop.img/CSItemSearch/BtSearch |
| 4841 | SP_4841_UI_CASHSHOPIMG_CSITEMSEARCH_POPUP_BACKGRND | UI/CashShop.img/CSItemSearch/PopUp/backgrnd |
| 4842 | SP_4842_THIS_IS_YOUR_SEARCH_RESULT | This is your search result. |
| 4843 | SP_4843_ITEM_CASH_0528IMG_08D_TILE | Item/Cash/0528.img/%08d/tile |
| 4844 | SP_4844_ITEM_CASH_0528IMG_08D_EFFECT | Item/Cash/0528.img/%08d/effect |
| 4845 | SP_4845_SOUND_CONSUMEEFFECTIMG_D_USE | Sound/ConsumeEffect.img/%d/Use |
| 4846 | SP_4846_AN_EFFECT_ITEM_IS_CURRENTLY_IN_USE__R_NPLEASE_TRY_AGAIN_LATER | An Effect Item is currently in use. \r\nplease try again later. |
| 4847 | SP_4847_RECALL | recall |
| 4848 | SP_4848__PETCOME | /petcome |
| 4849 | SP_4849_AUTOREACT | autoReact |
| 4850 | SP_4850_ELEMDEFAULT | elemDefault |
| 4851 | SP_4851_INCRMAS | incRMAS |
| 4852 | SP_4852_INCRMAI | incRMAI |
| 4853 | SP_4853_INCRMAF | incRMAF |
| 4854 | SP_4854_INCRMAL | incRMAL |
| 4855 | SP_4855_INCRMAH | incRMAH |
| 4856 | SP_4856_UI_UIWINDOWIMG_TREASUREBOX_D_BACKGRND | UI/UIWindow.img/TreasureBox/%d/backgrnd |
| 4857 | SP_4857_UI_UIWINDOWIMG_TREASUREBOX_D_BACKGRNDOPENED | UI/UIWindow.img/TreasureBox/%d/backgrndOpened |
| 4858 | SP_4858_UI_UIWINDOWIMG_TREASUREBOX_D_BACKGRNDCLOSED | UI/UIWindow.img/TreasureBox/%d/backgrndClosed |
| 4859 | SP_4859_THE_SNOWMAN_ABSORBED_THE_SNOW_VIGOR | The Snowman absorbed the Snow Vigor! |
| 4860 | SP_4860_YOULL_NEED_TO_USE_ALL_YOUR_REMAINING_EXP_IN_ORDER_TO_USE_A_NEW_WRIT_OF_SOLOMON | You'll need to use all your remaining EXP in order to use a new Writ of Solomon. |
| 4861 | SP_4861_DO_YOU_WISH_TO_CHARGE_THE_EXP__R_NEARNED_FROM_THE_WRITS_OF_SOLOMON | Do you wish to charge the EXP \r\nearned from The Writs of Solomon? |
| 4862 | SP_4862_ARE_YOU_SURE_TO_SEND_AN_R_NEMPTY_NEW_YEAR_CARD_ | Are you sure to send an\r\nempty new year card ? |
| 4863 | SP_4863_YOU_CANNOT_SEND_A_CARD_TO_YOURSELF_ | You cannot send a card to yourself ! |
| 4864 | SP_4864_YOU_HAVE_NO_FREE_SLOT_TO_STORE_CARD_R_NTRY_LATER_ON_PLEASE | You have no free slot to store card.\r\ntry later on please. |
| 4865 | SP_4865_YOU_HAVE_NO_CARD_TO_SEND | You have no card to send. |
| 4866 | SP_4866_WRONG_INVENTORY_INFORMATION_ | Wrong inventory information ! |
| 4867 | SP_4867_CANNOT_FIND_SUCH_CHARACTER_ | Cannot find such character ! |
| 4868 | SP_4868_INCOHERENT_DATA_ | Incoherent Data ! |
| 4869 | SP_4869_AN_ERROR_OCCURED_DURING_DB_OPERATION | An error occured during DB operation. |
| 4870 | SP_4870_AN_UNKNOWN_ERROR_OCCURED_ | An unknown error occured ! |
| 4871 | SP_4871_SUCCESSFULLY_SENT_A_NEW_YEAR_CARD_R_N_TO_S | Successfully sent a New Year Card\r\n to %s. |
| 4872 | SP_4872_SUCCESSFULLY_RECEIVED_A_NEW_YEAR_CARD | Successfully received a New Year Card. |
| 4873 | SP_4873_SUCCESSFULLY_DELETED_A_NEW_YEAR_CARD | Successfully deleted a New Year Card. |
| 4874 | SP_4874_FROM_S__N_R_S__N_R_______TO_S | From. %s \n\r %s... \n\r       To. %s |
| 4875 | SP_4875_WOULD_YOU_LIKE_TO_THROW_R_NAWAY_THIS_CARD | Would you like to throw\r\naway this card? |
| 4876 | SP_4876_YOU_TRANSFORMED_S_INTO_SOME_OTHER_FORM | You transformed %s into some other form. |
| 4877 | SP_4877_S_TRANSFORMED_YOU_INTO_SOME_STRANGE_LOOK | %s transformed you into some strange look. |
| 4878 | SP_4878_FAILED_TO_FIND_USER_S | Failed to find user %s. |
| 4879 | SP_4879_YOU_CAN_USE_RANDOM_TRANSFORM_POTION_ONLY_IN_THE_TOWN | \tYou can use random transform potion only in the town. |
| 4880 | SP_4880_UI_CASHSHOPIMG_CSGIFT_BTBUDDY | UI/CashShop.img/CSGift/BtBuddy |
| 4881 | SP_4881_UI_CASHSHOPIMG_CSGIFT_BTGUILD | UI/CashShop.img/CSGift/BtGuild |
| 4882 | SP_4882_UI_CASHSHOPIMG_CSGIFT_BTHIDE | UI/CashShop.img/CSGift/BtHide |
| 4883 | SP_4883_UI_CASHSHOPIMG_CSGIFT_BACKGRND | UI/CashShop.img/CSGift/backgrnd |
| 4884 | SP_4884_UI_CASHSHOPIMG_CSGIFT_BACKGRND1 | UI/CashShop.img/CSGift/backgrnd1 |
| 4885 | SP_4885_THE_SNOWMAN_ABSORBED_THE_FAKE_SNOW_VIGOR | The Snowman absorbed the fake Snow Vigor... |
| 4886 | SP_4886_DISTANCEX | distanceX |
| 4887 | SP_4887_DISTANCEY | distanceY |
| 4888 | SP_4888_MAXDIFF | maxDiff |
| 4889 | SP_4889_ONOFFDEFAULT | onoffdefault |
| 4890 | SP_4890_FORCEHP | forceHP |
| 4891 | SP_4891_UI_UIWINDOWIMG_REMOTEGACHAPON_BACKGRND | UI/UIWindow.img/RemoteGachapon/backgrnd |
| 4892 | SP_4892_UI_UIWINDOWIMG_REMOTEGACHAPON_BT | UI/UIWindow.img/RemoteGachapon/Bt |
| 4893 | SP_4893_SORT_BY_PRICE | SORT BY PRICE |
| 4894 | SP_4894_SKILL_DRAGON_DIMG | Skill/Dragon/%d.img |
| 4895 | SP_4895_HACKSHIELD_ERROR__0X08XD_R_N_R_N | HackShield Error : 0x%08x(%d)\r\n\r\n |
| 4896 | SP_4896_HACKSHIELD_INITIALIZATION_ERROR__0X08XD_R_N_R_N | HackShield Initialization Error : 0x%08x(%d)\r\n\r\n |
| 4897 | SP_4897_HACKSHIELD_UPDATE_ERROR__0X08XD_R_N_R_N | HackShield Update Error : 0x%08x(%d)\r\n\r\n |
| 4898 | SP_4898_CANNOT_READ_HSUPDATEENV_FILE | Cannot read HSUpdate.env file. |
| 4899 | SP_4899_CANNOT_WRITE_HSUPDATEENV_FILE | Cannot write HSUpdate.env file. |
| 4900 | SP_4900_CANNOT_CONNECT_TO_THE_SERVER | Cannot connect to the server. |
| 4901 | SP_4901_AN_ERROR_OCCURRED_WHILE_INPUTTING_THE_NETWORK_RESULT | An error occurred while inputting the network result. |
| 4902 | SP_4902_CANNOT_FILE_HACKSHIELD_UPDATE_PROGRAM_RELATED_FILES | Cannot file HackShield update program-related files. |
| 4903 | SP_4903_CANNOT_FILE_HSUPDATEPT_AUTHENTICATION_FILE | Cannot file HSUpdate.pt authentication file. |
| 4904 | SP_4904_UPDATE_TIMEOUT_OCCURRED_IN_HACKSHIELD_UPDATE_PROGRAM | Update timeout occurred in HackShield update program. |
| 4905 | SP_4905_ERROR_OCCURS_WHEN_ANOTHER_GAME_CLIENT_OR_ANOTHER_PROCESS_IS_ALREADY_EXECUTING_HA | Error occurs when another game client or another process is already executing HackShield. Since the HackShield vr.3.1 has been improved to allow HackShield running in a multi-process environment, this does not occur in the newer version clients. (Value = 0x201) |
| 4906 | SP_4906_ERROR_OCCURS_WHEN_THE_GAME_CLIENT_IS_EXECUTED_IN_THE_COMPATIBILITY_MODE_PROVIDED | Error occurs when the game client is executed in the Compatibility Mode provided by Windows XP. Game execution in Compatibility Mode is judged as a user with ill intentions, so program must be ended and restarted. (Value = 0x004) |
| 4907 | SP_4907_ERROR_OCCURS_WHEN_GAME_CLIENT_IS_EXECUTED_IN_A_GENERAL_USER_ACCOUNT_S_AUTHORITY_ | Error occurs when game client is executed in a general user account' s authority, not in the Windows NT system's administrator account. In order to use the functions provided by HackShield with a general user accoun'ts authority, a HackShield Shadow account must first be created. Refer to ‘User Authority Execution Function'. |
| 4908 | SP_4908_ERROR_OCCURS_WHEN_THE_SYSTEM_HAS_ALREADY_FINISHED_INITIALIZATION_THROUGH_CALLING | Error occurs when the system has already finished initialization through calling _AhnHS_Initialize. _AhnHS_Initialize can only be called once at the program initialization stage, and cannot be called repeatedly. This can only occur in the developmental process, so no other processes need to be taken. (Value = 0x001) |
| 4909 | SP_4909_ERROR_OCCURS_WHEN_THE_POINTER_OF_THE_CALLBACK_FUNCTION_IS_SET_INCORRECTLY_OR_IF_ | Error occurs when the pointer of the Callback function is set incorrectly or if the license key value is NULL. This can only occur in the developmental process, so no other processes need to be taken. (Value = 0x002) |
| 4910 | SP_4910_ERROR_OCCURS_WHEN_THE_GAME_CODE_SET_TO_INITIALIZATION_FUNCTION_S_PARAMETER_AND_T | Error occurs when the game code set to initialization function' s parameter and the license key value do not match the actual values. This can only occur in the developmental process, so no other processes need to be taken. (Value = 0x100) |
| 4911 | SP_4911_ERROR_OCCURS_WHEN_THE_INTERFACE_DLL_FILEEHSVCDLL_EGRNAPDLL_EGRNAPX2DLL_IS_FORGED | Error occurs when the interface DLL file(EhSvc.dll, EGRNAP.dll, EGRNAPX2.dll) is forged or altered, or when the version is not matching. If the interface DLL file is forged or altered, this may lead to incapacitation of the hack prevention function. Program must be ended and restarted. (Value = 0x101) |
| 4912 | SP_4912_ERROR_OCCURS_WHEN_THE_HACK_PREVENTION_FUNCTION_DRIVER_FAILS_INITIALIZATION_IF_DR | Error occurs when the hack prevention function Driver fails initialization. If driver initialization fails, the hack prevention function will not work normally, so program should be ended, and restarted. (Value = 0x102) |
| 4913 | SP_4913_ERROR_OCCURS_WHILE_DEBUGGER_IS_RUNNING_ON_THE_SYSTEM_IF_THIS_OCCURS_DURING_THE_D | Error occurs while Debugger is running on the system. If this occurs during the developmental process, exclude option AHNHS_CHKOPT_KDTRACER. If this occurs in the version distributed to users, end game program and debugger, and re-execute as there is a possibility of hack attacks. (Value = 0x105) |
| 4914 | SP_4914_YOU_ARE_DISCONNECTED_FROM_MAPLESTROY_DUE_TO_THE_ATTEMPTING_TO_HACK_IF_YOU_HAVE_A | You are disconnected from MapleStroy due to the attempting to hack. If you have any important issue, please contact us via our homepage |
| 4915 | SP_4915__R_N_R_NPLEASE_SEND_THE_DETAILED_INFORMATION_THROUGH_AHNREPORT | \r\n\r\nPlease send the detailed information through AhnReport |
| 4916 | SP_4916_UNABLE_TO_RECEIVE_SKIN_FILE__N_PLEASE_TRY_AGAIN_LATER | Unable to receive skin file. \n Please try again later. |
| 4917 | SP_4917_UNABLE_TO_READ_AUTHENTICATION_FILE | Unable to read authentication file. |
| 4918 | SP_4918_FAILED_TO_ANALYZE_PRODUCT_SETUP_AHNUI_FILE | Failed to analyze product setup ahn.ui file. |
| 4919 | SP_4919_UNABLE_TO_RECEIVE_PRODUCT_SETUP_AHNUI_FILE__NPLEASE_TRY_AGAIN_LATER | Unable to receive product setup ahn.ui file. \nPlease try again later. |
| 4920 | SP_4920_FAILED_TO_VERIFY_PRODUCT_SETUP | Failed to verify product setup. |
| 4921 | SP_4921_FAILED_TO_CALL_AHNUPEXDLL | Failed to call AhnUpEx.dll. |
| 4922 | SP_4922_FAILED_TO_CALL_ASPUPDLL | Failed to call AspUp.dll. |
| 4923 | SP_4923_UNABLE_TO_RECEIVE_SKIN_FILE__NPLEASE_TRY_AGAIN_LATER | Unable to receive skin file. \nPlease try again later. |
| 4924 | SP_4924_FAILED_TO_CALL_ASPSNCDLL | Failed to call ASPSnc.dll. |
| 4925 | SP_4925_UNABLE_TO_RECEIVE_AUTHENTICATION_FILE__NPLEASE_TRY_AGAIN_LATER | Unable to receive authentication file. \nPlease try again later. |
| 4926 | SP_4926_UNABLE_TO_SAVE_AUTHENTICATION_FILE_WITH_PRODUCT | Unable to save authentication file with product. |
| 4927 | SP_4927_UPDATE_IS_ALREADY_IN_PROGRESS_SUIPRE | Update is already in progress. (SUiPre) |
| 4928 | SP_4928_UNABLE_TO_EXECUTE_DUE_TO_UNSUPPORTED_OS | Unable to execute due to unsupported OS. |
| 4929 | SP_4929_YOU_HAVE_LOGGED_INTO_THE_ACCOUNT_WITHOUT_ADMINISTRATOR_AUTHORITY__R_NPROGRAM_CAN | You have logged into the account without Administrator authority. \r\nProgram cannot be installed or used without the Administrator authority. \r\nPlease log in through account with Administrator authority. |
| 4930 | SP_4930_UNABLE_TO_RECEIVE_FILE__NPLEASE_TRY_AGAIN_LATER | Unable to receive file. \nPlease try again later. |
| 4931 | SP_4931_FAILED_TO_ANALYZE_AHNUI_FILE_OF_UPDATE_PROGRAM | Failed to analyze ahn.ui file of update program. |
| 4932 | SP_4932_FAILED_TO_CALL_HELPERDLL | Failed to call Helper.dll. |
| 4933 | SP_4933_UNABLE_TO_RECEIVE_UPDATE_PROGRAMS_MODULE | Unable to receive update program's module. |
| 4934 | SP_4934_UNABLE_TO_RECEIVE_UPDATE_PROGRAMS_SETUP | Unable to receive update program's setup. |
| 4935 | SP_4935_FAILED_TO_VERIFY_UPDATE_PROGRAM_SETUP | Failed to verify update program setup. |
| 4936 | SP_4936_FAILED_TO_VERIFY_UPDATE_PROGRAM_MODULE | Failed to verify update program module. |
| 4937 | SP_4937_AN_ERROR_OCCURRED_IN_THE_PRODUCT_INFORMATION | An error occurred in the product information. |
| 4938 | SP_4938_UNABLE_TO_EXECUTE_1_FILE | Unable to execute %1 file. |
| 4939 | SP_4939_FAILED_TO_UNZIP | Failed to unzip. |
| 4940 | SP_4940_FAILED_TO_MOVE_UPDATE_PROGRAM_MODULE | Failed to move update program module. |
| 4941 | SP_4941_AUTHENTICATION_FILE_HAS_EXPIRED | Authentication file has expired. |
| 4942 | SP_4942_AN_ERROR_OCCURRED_IN_SMART_UPDATE_I_INSTALLATION_PROGRAM | An error occurred in Smart Update i installation program. |
| 4943 | SP_4943_CANNOT_EXECUTE_DUE_TO_UNSUPPORTED_USER_AUTHORITY | Cannot execute due to unsupported user authority. |
| 4944 | SP_4944_UNSUPPORTED_VERSION_OF_MICROSOFT_INTERNET_EXPLORER | Unsupported version of Microsoft Internet Explorer. |
| 4945 | SP_4945_UNSUPPORTED_CPU | Unsupported CPU. |
| 4946 | SP_4946_YOU_DO_NOT_HAVE_ACCESS_TO_WRITE_FILE | You do not have access to write file. |
| 4947 | SP_4947_COULD_NOT_EXECUTE_SUIMAINEXE | Could not execute suimain.exe. |
| 4948 | SP_4948_NOT_ALLOWED_URL | Not Allowed URL |
| 4949 | SP_4949_THE_OPERATION_WAS_CANCELED_BY_THE_USER | The operation was canceled by the user. |
| 4950 | SP_4950_NOT_ALLOWED_PROCESS | Not Allowed Process |
| 4951 | SP_4951_SAFETY_MODE_IS_NOT_SUPPORTED | Safety mode is not supported. |
| 4952 | SP_4952_UNKNOWN_ERROR_OCCURRED | Unknown error occurred. |
| 4953 | SP_4953_FAILED_DURING_INITIALIZATION_CANCELLATION | Failed during initialization cancellation. |
| 4954 | SP_4954_FAILED_TO_LOAD_MODULES_MKD25DLL_MKD25DEFDLL | Failed to load modules Mkd25.dll, Mkd25def.dll. |
| 4955 | SP_4955_INITIALIZATION_WAS_NOT_COMPLETED | Initialization was not completed. |
| 4956 | SP_4956_ABNORMAL_PARAMETER | Abnormal parameter. |
| 4957 | SP_4957_FAILED_DURING_INITIALIZATION | Failed during initialization. |
| 4958 | SP_4958_AOS_MKD_MODULE_WAS_NOT_LOADED | AOS MKD module was not loaded. |
| 4959 | SP_4959_UPDATE_IS_NOT_SUPPORTED | Update is not supported. |
| 4960 | SP_4960_FAILED_DURING_UPDATE_PROCESS | Failed during update process. |
| 4961 | SP_4961_ABNORMAL_UPDATE_PARAMETER | Abnormal update parameter. |
| 4962 | SP_4962_FAILED_TO_LOAD_UPDATE_MODULE | Failed to load update module. |
| 4963 | SP_4963_AOS_MKD_IS_NOT_INSTALLED | AOS MKD is not installed. |
| 4964 | SP_4964_AN_ERROR_OCCURRED_IN_AOS_MKD_COMPATIBILITY__R_NPLEASE_TERMINATE_ALL_MKD_LINKED_P | An error occurred in AOS MKD compatibility. \r\nPlease terminate all MKD linked productings and re-execute. |
| 4965 | SP_4965_UNPROTECTED_EDIT_CONTROL | Unprotected Edit Control. |
| 4966 | SP_4966_UNMATCHING_PROPERTIES | Unmatching properties. |
| 4967 | SP_4967_SOME_API_HOOKING_IS_ALREADY_DONE_HOWEVER_THIS_IS_ACTUALLY_BLOCKED_AND_HOOKING_PR | Some API hooking is already done. (However, this is actually blocked and hooking program does not work.) |
| 4968 | SP_4968_DETECTED_AUTO_MOUSE_ACTION | Detected auto-mouse action. |
| 4969 | SP_4969_DETECTED_AUTO_MACRO_ACTION | Detected auto-macro action. |
| 4970 | SP_4970_DETECTED_HOOKING_ACTION_TOWARDS_PROTECTION_API | Detected hooking action towards protection API. |
| 4971 | SP_4971_HACK_PREVENTION_DRIVER_WAS_NOT_LOADED | Hack prevention driver was not loaded. |
| 4972 | SP_4972_MESSAGE_HOOKING_HAS_BEEN_DETECTED_THIS_COULD_NOT_BE_BLOCKED | Message hooking has been detected. This could not be blocked. |
| 4973 | SP_4973_DEBUGGER_TRACING_OCCURRED_KERNEL_DEBUGGER_ACTIVATION_THEN_BREAK_POINTER_MEASURE | Debugger tracing occurred. (Kernel debugger activation, then Break Pointer measure) |
| 4974 | SP_4974_CHANGE_OCCURRED_IN_INSTALLED_DEBUGGER_TRACING | Change occurred in installed debugger tracing. |
| 4975 | SP_4975_IF_SPEED_HACK_DETECTION_OPTION_IS_GAME_RECENT_5_SECONDS_OF_TIME_INFORMATION_IS_S | If speed hack detection option is 'GAME,' recent 5 seconds of time information is sent through this callback. |
| 4976 | SP_4976_DETECTED_GAME_HACK_TOOL_EXECUTION | Detected game hack tool execution. |
| 4977 | SP_4977_DETECTED_REGULAR_HACK_TOOL_TROJAN_HORSEETC | Detected regular hack tool (Trojan Horse,etc). |
| 4978 | SP_4978_WINDOW_MODE_HACK_TOOL | Window-mode hack tool |
| 4979 | SP_4979_DETECTED_CODE_PATCH_TRIAL | Detected code patch trial. |
| 4980 | SP_4980_CHANGE_OCCURRED_IN_HACKSHIELD_RELATED_MODULE | Change occurred in Hackshield-related module. |
| 4981 | SP_4981_ABNORMAL_ENGINE_FUNCTION | Abnormal engine function. |
| 4982 | SP_4982_UNMATCHING_CODE | Unmatching code. |
| 4983 | SP_4983_ABNORMAL_SCREENSAVER_FUNCTION | Abnormal screensaver function. |
| 4984 | SP_4984_DETECTED_MEMORY_ALTERATION_TOWARDS_PROTECTION_MODULE | Detected memory alteration towards protection module. |
| 4985 | SP_4985_CODE_IN_GAME_EXE_IS_CALLED_RANDOMLY_FROM_EXTERNAL_MODULE | Code in Game EXE is called randomly from external module. |
| 4986 | SP_4986_GAME_DID_NOT_CONNECT_TO_CORRECT_SERVER | Game did not connect to correct server. |
| 4987 | SP_4987_IRREGULAR_INFORMATION_ACCORDING_TO_HACKSHIELD_MOTION_CHECK | Irregular information according to HackShield motion check. |
| 4988 | SP_4988_S__GOT_AN | %s : got a(n) |
| 4989 | SP_4989_S_S____S__FROM_THE_S_GACHAPON_CONGRATS | %s %s  :  %s  from the %s Gachapon! Congrats! |
| 4990 | SP_4990_S_S____S__FROM_THE_CASH_SHOP_SURPRISE_CONGRATS | %s %s  :  %s  from the Cash Shop Surprise! Congrats! |
| 4991 | SP_4991_UI_UIWINDOWIMG_CHARACTERCLONE_MAINSTEP1 | UI/UIWindow.img/CharacterClone/MainStep1 |
| 4992 | SP_4992_UI_UIWINDOWIMG_CHARACTERCLONE_MAINSTEP2 | UI/UIWindow.img/CharacterClone/MainStep2 |
| 4993 | SP_4993_UI_UIWINDOWIMG_CHARACTERCLONE_MAINSTEP3 | UI/UIWindow.img/CharacterClone/MainStep3 |
| 4994 | SP_4994_UI_UIWINDOWIMG_CHARACTERCLONE_MAINSTEP4 | UI/UIWindow.img/CharacterClone/MainStep4 |
| 4995 | SP_4995_UI_UIWINDOWIMG_CHARACTERCLONE_MAINSTEP5 | UI/UIWindow.img/CharacterClone/MainStep5 |
| 4996 | SP_4996_UI_UIWINDOWIMG_CHARACTERCLONE_APPEARANCE | UI/UIWindow.img/CharacterClone/Appearance |
| 4997 | SP_4997_UI_UIWINDOWIMG_CHARACTERCLONE_BTCANCEL | UI/UIWindow.img/CharacterClone/BtCancel |
| 4998 | SP_4998_UI_UIWINDOWIMG_CHARACTERCLONE_BTNEXT | UI/UIWindow.img/CharacterClone/BtNext |
| 4999 | SP_4999_UI_UIWINDOWIMG_CHARACTERCLONE_BTBACK | UI/UIWindow.img/CharacterClone/BtBack |
| 5000 | SP_5000_UI_UIWINDOWIMG_CHARACTERCLONE_BTFINISH | UI/UIWindow.img/CharacterClone/BtFinish |
| 5001 | SP_5001_UI_UIWINDOWIMG_CHARACTERCLONE_BTLEFT | UI/UIWindow.img/CharacterClone/BtLeft |
| 5002 | SP_5002_UI_UIWINDOWIMG_CHARACTERCLONE_BTRIGHT | UI/UIWindow.img/CharacterClone/BtRight |
| 5003 | SP_5003_UI_UIWINDOWIMG_CHARACTERCLONE_WARRIOR_0 | UI/UIWindow.img/CharacterClone/Warrior/0 |
| 5004 | SP_5004_UI_UIWINDOWIMG_CHARACTERCLONE_MAGICIAN_0 | UI/UIWindow.img/CharacterClone/Magician/0 |
| 5005 | SP_5005_UI_UIWINDOWIMG_CHARACTERCLONE_ARCHER_0 | UI/UIWindow.img/CharacterClone/Archer/0 |
| 5006 | SP_5006_UI_UIWINDOWIMG_CHARACTERCLONE_THIEF_0 | UI/UIWindow.img/CharacterClone/Thief/0 |
| 5007 | SP_5007_UI_UIWINDOWIMG_CHARACTERCLONE_PIRATE_0 | UI/UIWindow.img/CharacterClone/Pirate/0 |
| 5008 | SP_5008_UI_UIWINDOWIMG_CHARACTERCLONE_WARRIOR_1 | UI/UIWindow.img/CharacterClone/Warrior/1 |
| 5009 | SP_5009_UI_UIWINDOWIMG_CHARACTERCLONE_MAGICIAN_1 | UI/UIWindow.img/CharacterClone/Magician/1 |
| 5010 | SP_5010_UI_UIWINDOWIMG_CHARACTERCLONE_ARCHER_1 | UI/UIWindow.img/CharacterClone/Archer/1 |
| 5011 | SP_5011_UI_UIWINDOWIMG_CHARACTERCLONE_THIEF_1 | UI/UIWindow.img/CharacterClone/Thief/1 |
| 5012 | SP_5012_UI_UIWINDOWIMG_CHARACTERCLONE_PIRATE_1 | UI/UIWindow.img/CharacterClone/Pirate/1 |
| 5013 | SP_5013_S_R_N_WILL_BE_SEALED_UNTIL_D_D_04D_02D02D_R_NDO_YOU_WANT_TO_PROCEED | %s\r\n will be sealed until %d-%d-%04d %02d:%02d.\r\nDo you want to proceed? |
| 5014 | SP_5014_S_R_N_WILL_EXTEND_THE_ITEM_SEAL_UNTIL_D_D_04D_02D02D_R_N_DO_YOU_WANT_TO_PROCEED | %s\r\n will extend the item seal until %d-%d-%04d %02d:%02d.\r\n Do you want to proceed? |
| 5015 | SP_5015_SS_SEAL_HAS_EXPIRED | %s's seal has expired. |
| 5016 | SP_5016_SS_SEAL_EXPIRES_ON_D_D_04D_02D02D_IF_YOU_ADDITIONALLY_USE_THE_TIMED_SEAL_ITEM_YO | %s's seal expires on %d-%d-%04d %02d:%02d. If you additionally use the timed seal item, you can extend the date. |
| 5017 | SP_5017_YOU_HAVE_TO_QUIT_FAMILY__R_NTO_MOVE_TO_ANOTHER_WORLD | You have to quit Family \r\nto move to another world. |
| 5018 | SP_5018_YOU_CANNOT_ADD_A_JUNIOR__R_NSINCE_YOUVE_REQUESTED_TO_CHANGE_WORLDS | You cannot add a Junior \r\nsince you've requested to change worlds. |
| 5019 | SP_5019_YOU_CANNOT_ADD_A_JUNIOR__R_NTHAT_HAS_REQUESTED_TO_CHANGE_WORLDS | You cannot add a Junior \r\nthat has requested to change worlds. |
| 5020 | SP_5020_DONT_SPEND_SP_INCREASE_MAX_HP_0 | Don't spend SP, increase MAX HP 0 |
| 5021 | SP_5021_SPEND_SP_6_INCREASE_MAX_HP_72 | spend SP 6, increase MAX HP 72 |
| 5022 | SP_5022_SPEND_SP_7_INCREASE_MAX_HP_144 | spend SP 7, increase MAX HP 144 |
| 5023 | SP_5023_SPEND_SP_8_INCREASE_MAX_HP_212 | spend SP 8, increase MAX HP 212 |
| 5024 | SP_5024_SPEND_SP_9_INCREASE_MAX_HP_280 | spend SP 9, increase MAX HP 280 |
| 5025 | SP_5025_SPEND_SP_10_INCREASE_MAX_HP_348 | spend SP 10, increase MAX HP 348 |
| 5026 | SP_5026_SPEND_SP_11_INCREASE_MAX_HP_412 | spend SP 11, increase MAX HP 412 |
| 5027 | SP_5027_SPEND_SP_12_INCREASE_MAX_HP_476 | spend SP 12, increase MAX HP 476 |
| 5028 | SP_5028_SPEND_SP_13_INCREASE_MAX_HP_540 | spend SP 13, increase MAX HP 540 |
| 5029 | SP_5029_SPEND_SP_14_INCREASE_MAX_HP_600 | spend SP 14, increase MAX HP 600 |
| 5030 | SP_5030_SPEND_SP_15_INCREASE_MAX_HP_660 | spend SP 15, increase MAX HP 660 |
| 5031 | SP_5031_DONT_SPEND_SP_INCREASE_MAX_MP_0 | Don't spend SP, increase MAX MP 0 |
| 5032 | SP_5032_SPEND_SP_6_INCREASE_MAX_MP_40 | spend SP 6, increase MAX MP 40 |
| 5033 | SP_5033_SPEND_SP_7_INCREASE_MAX_MP_80 | spend SP 7, increase MAX MP 80 |
| 5034 | SP_5034_SPEND_SP_8_INCREASE_MAX_MP_118 | spend SP 8, increase MAX MP 118 |
| 5035 | SP_5035_SPEND_SP_9_INCREASE_MAX_MP_156 | spend SP 9, increase MAX MP 156 |
| 5036 | SP_5036_SPEND_SP_10_INCREASE_MAX_MP_194 | spend SP 10, increase MAX MP 194 |
| 5037 | SP_5037_SPEND_SP_11_INCREASE_MAX_MP_230 | spend SP 11, increase MAX MP 230 |
| 5038 | SP_5038_SPEND_SP_12_INCREASE_MAX_MP_266 | spend SP 12, increase MAX MP 266 |
| 5039 | SP_5039_SPEND_SP_13_INCREASE_MAX_MP_302 | spend SP 13, increase MAX MP 302 |
| 5040 | SP_5040_SPEND_SP_14_INCREASE_MAX_MP_336 | spend SP 14, increase MAX MP 336 |
| 5041 | SP_5041_SPEND_SP_15_INCREASE_MAX_MP_370 | spend SP 15, increase MAX MP 370 |
| 5042 | SP_5042_YOU_MUST_BE_OVER_LEVEL_30_R_NTO_PURCHASE_THIS_ITEM | You must be over Level 30\r\nto purchase this item |
| 5043 | SP_5043_YOU_DO_NOT_HAVE_ANY_EMPTY_CHARACTER_SLOTS_R_NTHE_MAPLE_LIFEB_TYPE_WILL_CREATE_R_ | You do not have any empty character slots\r\nThe Maple life(B-Type) will create\r\na new character slot upon purchase. |
| 5044 | SP_5044_YOU_CANNOT_CREATE_A_NEW_CHARACTER_R_NIN_THIS_WORLD_AS_ALL_12_SLOTS_ARE_FULL | You cannot create a new character\r\nin this world as all 12 slots are full. |
| 5045 | SP_5045_NO_MORE_CHARACTERS_CAN_BE_CREATED_R_NPLEASE_CHECK_AGAIN | no more characters can be created.\r\nPlease check again |
| 5046 | SP_5046_YOU_CAN_NOT_USE_THIS_NAME_R_NPLEASE_CHECK_AGAIN | You can not use this name.\r\nPlease check again |
| 5047 | SP_5047_THIS_NAME_IS_CURRENTLY_BEING_USED_R_NPLEASE_CHECK_AGAIN | This name is currently being used.\r\nPlease check again. |
| 5048 | SP_5048_CREATION_HAS_COMPLETED_SUCCESSFULLY | Creation has completed successfully. |
| 5049 | SP_5049_UI_UIWINDOWIMG_VICIOUSHAMMER_BACKGRND | UI/UIWindow.img/ViciousHammer/backgrnd |
| 5050 | SP_5050_UI_UIWINDOWIMG_VICIOUSHAMMER_BTSTART | UI/UIWindow.img/ViciousHammer/BtStart |
| 5051 | SP_5051_UI_UIWINDOWIMG_VICIOUSHAMMER_BTCANCEL | UI/UIWindow.img/ViciousHammer/BtCancel |
| 5052 | SP_5052_THE_ITEM_IS_NOT_UPGRADABLE | The item is not upgradable. |
| 5053 | SP_5053_2_UPGRADE_INCREASES_R_NHAVE_BEEN_USED_ALREADY | 2 upgrade increases\r\nhave been used already. |
| 5054 | SP_5054_INCREASED_AVAILABLE_UPGRADE_BY_1__R_ND_UPGRADES_ARE_LEFT | \tIncreased available upgrade by 1. \r\n%d upgrades are left. |
| 5055 | SP_5055_YOU_CANT_USE_VICIOUS_HAMMER_R_NON_HORNTAIL_NECKLACE | You can't use Vicious' Hammer\r\non Horntail Necklace. |
| 5056 | SP_5056_NUMBER_OF_VICIOUS_HAMMER_APPLIED__D | NUMBER OF VICIOUS' HAMMER APPLIED : %d |
| 5057 | SP_5057_NUMBER_OF_VICIOUS_HAMMER_APPLIED__2MAX | NUMBER OF VICIOUS' HAMMER APPLIED : 2(MAX) |
| 5058 | SP_5058_VICIOUSHAMMER | ViciousHammer |
| 5059 | SP_5059_CANNOT_KICK_ANOTHER_USER_IN_THIS_MAP | Cannot kick another user in this map |
| 5060 | SP_5060__TALENT_SHOW_EVENT__THE_TALENT_SHOW_EVENT_HAS_STARTED_PLEASE_TALK_TO_NPC_GAGA_IN | [Talent Show event] The Talent Show event has started. Please talk to NPC Gaga in towns to participate. |
| 5061 | SP_5061_CASHGACHAPONIMG | CashGachapon.img |
| 5062 | SP_5062_PROB | Prob |
| 5063 | SP_5063_THIS_ITEM_CANNOT_BE_PURCHASED__R_NWITH_MAPLEPOINTS | This item cannot be purchased \r\nwith MaplePoints. |
| 5064 | SP_5064_SORRY_FOR_INCONVINENCE__R_NPLEASE_TRY_AGAIN | Sorry for inconvinence. \r\nplease try again. |
| 5065 | SP_5065_UI_UIWINDOWIMG_CASHGACHAPON_BACKGRND | UI/UIWindow.img/CashGachapon/backgrnd |
| 5066 | SP_5066_UI_UIWINDOWIMG_CASHGACHAPON_BTOPEN | UI/UIWindow.img/CashGachapon/BtOpen |
| 5067 | SP_5067_UI_UIWINDOWIMG_CASHGACHAPON_BTCLOSE | UI/UIWindow.img/CashGachapon/BtClose |
| 5068 | SP_5068_ACCOUNTSHARABLE | accountSharable |
| 5069 | SP_5069_ALERT6 | alert6 |
| 5070 | SP_5070_BACKHAIRBELOWCAP | backHairBelowCap |
| 5071 | SP_5071_COMBATSTEP | combatStep |
| 5072 | SP_5072_DOUBLEATTACK | doubleAttack |
| 5073 | SP_5073_FLAMEGEAR | flamegear |
| 5074 | SP_5074_FULLSWINGDOUBLE | fullSwingDouble |
| 5075 | SP_5075_FULLSWINGTRIPLE | fullSwingTriple |
| 5076 | SP_5076_ICESTORM | icestorm |
| 5077 | SP_5077_OVERSWINGDOUBLE | overSwingDouble |
| 5078 | SP_5078_OVERSWINGTRIPLE | overSwingTriple |
| 5079 | SP_5079_PHANTOM | phantom |
| 5080 | SP_5080_POLEARMFINISH | poleArmFinish |
| 5081 | SP_5081_POLEARMPUSH | poleArmPush |
| 5082 | SP_5082_POLEARMSMASH | poleArmSmash |
| 5083 | SP_5083_POLEARMTOSS | poleArmToss |
| 5084 | SP_5084_SOULDRIVER | souldriver |
| 5085 | SP_5085_STORMBREAK | stormbreak |
| 5086 | SP_5086_SWINGP1POLEARM | swingP1PoleArm |
| 5087 | SP_5087_SWINGP2POLEARM | swingP2PoleArm |
| 5088 | SP_5088_SWINGT2POLEARM | swingT2PoleArm |
| 5089 | SP_5089_TRIPLEATTACK | tripleAttack |
| 5090 | SP_5090_WHIRLWIND | whirlwind |
| 5091 | SP_5091_WINDSPEAR | windspear |
| 5092 | SP_5092_BATTLEFIELD | battleField |
| 5093 | SP_5093_MAP_OBJ_ETCIMG_BATTLEFIELD_BACKGRND | Map/Obj/etc.img/battleField/backgrnd |
| 5094 | SP_5094_MAP_OBJ_ETCIMG_BATTLEFIELD_FONTSCORE0 | Map/Obj/etc.img/battleField/fontScore0 |
| 5095 | SP_5095_MAP_OBJ_ETCIMG_BATTLEFIELD_FONTSCORE1 | Map/Obj/etc.img/battleField/fontScore1 |
| 5096 | SP_5096_MAP_OBJ_ETCIMG_BATTLEFIELD_FONTTIME | Map/Obj/etc.img/battleField/fontTime |
| 5097 | SP_5097_GOCHECKBOARDINDEX | goCheckBoardIndex |
| 5098 | SP_5098_MAP_OBJ_ETCIMG_EVENTPOINTCOUNT_BACKGRND | Map/Obj/etc.img/eventPointCount/backgrnd |
| 5099 | SP_5099_MAP_OBJ_ETCIMG_EVENTPOINTCOUNT | Map/Obj/etc.img/eventPointCount |
| 5100 | SP_5100_EFFECT_BASICEFFIMG_SOULRUSH | Effect/BasicEff.img/SoulRush |
| 5101 | SP_5101_HORIZONTALIMPACT | horizontalImpact |
| 5102 | SP_5102_REACTORNAME | reactorName |
| 5103 | SP_5103_HRANGE | hRange |
| 5104 | SP_5104_INFOEX | infoex |
| 5105 | SP_5105_VIEWMEDALITEM | viewMedalItem |
| 5106 | SP_5106_MEDALCATEGORY | medalCategory |
| 5107 | SP_5107_NOCANCELDAMAGE | noCancelDamage |
| 5108 | SP_5108_NOMAPCMD | noMapCmd |
| 5109 | SP_5109_NOTEXTEND | notExtend |
| 5110 | SP_5110_ONLYEQUIP | onlyEquip |
| 5111 | SP_5111_AUTOACCEPT | autoAccept |
| 5112 | SP_5112_REACTORSTATE | reactorState |
| 5113 | SP_5113_RACESELECT | RaceSelect |
| 5114 | SP_5114_MAP_OBJ_ETCIMG_SPACE_BACKGRND | Map/Obj/etc.img/space/backgrnd |
| 5115 | SP_5115_MAP_OBJ_ETCIMG_SPACE_FONTTIME | Map/Obj/etc.img/space/fontTime |
| 5116 | SP_5116_TARGETFIELD | targetField |
| 5117 | SP_5117_TARGETPORTAL | targetPortal |
| 5118 | SP_5118_UI_UIWINDOWIMG_GMBOARD_BACKGRND | UI/UIWindow.img/GMBoard/backgrnd |
| 5119 | SP_5119_UI_UIWINDOWIMG_GMBOARD_LOADING | UI/UIWindow.img/GMBoard/Loading |
| 5120 | SP_5120_UI_UIWINDOWIMG_GMBOARD_NOTICEICON | UI/UIWindow.img/GMBoard/NoticeIcon |
| 5121 | SP_5121_UI_LOGINIMG_CHARSELECT_ARAN | UI/Login.img/CharSelect/aran |
| 5122 | SP_5122_UI_LOGINIMG_CHARSELECT_ADVENTURE | UI/Login.img/CharSelect/adventure |
| 5123 | SP_5123_UI_LOGINIMG_NEWCHARARAN_AVATARSEL | UI/Login.img/NewCharAran/avatarSel |
| 5124 | SP_5124_UI_LOGINIMG_NEWCHARARAN_BTLEFT | UI/Login.img/NewCharAran/BtLeft |
| 5125 | SP_5125_UI_LOGINIMG_NEWCHARARAN_BTNO | UI/Login.img/NewCharAran/BtNo |
| 5126 | SP_5126_UI_LOGINIMG_NEWCHARARAN_BTRIGHT | UI/Login.img/NewCharAran/BtRight |
| 5127 | SP_5127_UI_LOGINIMG_NEWCHARARAN_BTYES | UI/Login.img/NewCharAran/BtYes |
| 5128 | SP_5128_UI_LOGINIMG_NEWCHARARAN_CHARNAME | UI/Login.img/NewCharAran/charName |
| 5129 | SP_5129_UI_LOGINIMG_NEWCHARARAN_CHARSET | UI/Login.img/NewCharAran/charSet |
| 5130 | SP_5130_UI_LOGINIMG_NEWCHARKNIGHT_AVATARSEL | UI/Login.img/NewCharKnight/avatarSel |
| 5131 | SP_5131_UI_LOGINIMG_NEWCHARKNIGHT_BTLEFT | UI/Login.img/NewCharKnight/BtLeft |
| 5132 | SP_5132_UI_LOGINIMG_NEWCHARKNIGHT_BTNO | UI/Login.img/NewCharKnight/BtNo |
| 5133 | SP_5133_UI_LOGINIMG_NEWCHARKNIGHT_BTRIGHT | UI/Login.img/NewCharKnight/BtRight |
| 5134 | SP_5134_UI_LOGINIMG_NEWCHARKNIGHT_BTYES | UI/Login.img/NewCharKnight/BtYes |
| 5135 | SP_5135_UI_LOGINIMG_NEWCHARKNIGHT_CHARNAME | UI/Login.img/NewCharKnight/charName |
| 5136 | SP_5136_UI_LOGINIMG_NEWCHARKNIGHT_CHARSET | UI/Login.img/NewCharKnight/charSet |
| 5137 | SP_5137_UI_CHATBALLOONIMG_TUTORIAL_S | UI/ChatBalloon.img/tutorial/%s |
| 5138 | SP_5138_UI_TUTORIALIMG_D | UI/tutorial.img/%d |
| 5139 | SP_5139_UI_LOGINIMG_RACESELECT_ARAN_BTARAN | UI/Login.img/RaceSelect/aran/BtAran |
| 5140 | SP_5140_UI_LOGINIMG_RACESELECT_KNIGHT_BTKNIGHT | UI/Login.img/RaceSelect/knight/BtKnight |
| 5141 | SP_5141_UI_LOGINIMG_RACESELECT_BTSELECT | UI/Login.img/RaceSelect/BtSelect |
| 5142 | SP_5142_UI_LOGINIMG_RACESELECT_NORMAL_BTNORMAL | UI/Login.img/RaceSelect/normal/BtNormal |
| 5143 | SP_5143_UI_LOGINIMG_RACESELECT_ARAN_OFFANIMATION | UI/Login.img/RaceSelect/aran/OffAnimation |
| 5144 | SP_5144_UI_LOGINIMG_RACESELECT_KNIGHT_OFFANIMATION | UI/Login.img/RaceSelect/knight/OffAnimation |
| 5145 | SP_5145_UI_LOGINIMG_RACESELECT_NORMAL_OFFANIMATION | UI/Login.img/RaceSelect/normal/OffAnimation |
| 5146 | SP_5146_UI_LOGINIMG_RACESELECT_ARAN_ONANIMATION | UI/Login.img/RaceSelect/aran/OnAnimation |
| 5147 | SP_5147_UI_LOGINIMG_RACESELECT_KNIGHT_ONANIMATION | UI/Login.img/RaceSelect/knight/OnAnimation |
| 5148 | SP_5148_UI_LOGINIMG_RACESELECT_NORMAL_ONANIMATION | UI/Login.img/RaceSelect/normal/OnAnimation |
| 5149 | SP_5149_UI_LOGINIMG_RACESELECT_ARAN_TEXT | UI/Login.img/RaceSelect/aran/text |
| 5150 | SP_5150_UI_LOGINIMG_RACESELECT_KNIGHT_TEXT | UI/Login.img/RaceSelect/knight/text |
| 5151 | SP_5151_UI_LOGINIMG_RACESELECT_NORMAL_TEXT | UI/Login.img/RaceSelect/normal/text |
| 5152 | SP_5152_UI_LOGINIMG_RACESELECT_TEXTGL_0 | UI/Login.img/RaceSelect/textGL/0 |
| 5153 | SP_5153_UI_UIWINDOWIMG_STAT_BASICSTAT | UI/UIWindow.img/Stat/basicStat |
| 5154 | SP_5154_UI_UIWINDOWIMG_CASHTRADINGROOM_BACKGRND | UI/UIWindow.img/CashTradingRoom/backgrnd |
| 5155 | SP_5155_UI_UIWINDOWIMG_CASHTRADINGROOM_BTCOIN | UI/UIWindow.img/CashTradingRoom/BtCoin |
| 5156 | SP_5156_UI_UIWINDOWIMG_CASHTRADINGROOM_BTRESET | UI/UIWindow.img/CashTradingRoom/BtReset |
| 5157 | SP_5157_UI_UIWINDOWIMG_CASHTRADINGROOM_BTTRADE | UI/UIWindow.img/CashTradingRoom/BtTrade |
| 5158 | SP_5158_UI_UIWINDOWIMG_CASHTRADINGROOM_ICON2 | UI/UIWindow.img/CashTradingRoom/icon2 |
| 5159 | SP_5159_UI_UIWINDOWIMG_CONTEXTMENU_BTCASHTRADE | UI/UIWindow.img/ContextMenu/BtCashTrade |
| 5160 | SP_5160_UI_UIWINDOWIMG_FADEYESNO_BACKGRND6 | UI/UIWindow.img/FadeYesNo/backgrnd6 |
| 5161 | SP_5161_UI_UIWINDOWIMG_FADEYESNO_ICON9 | UI/UIWindow.img/FadeYesNo/icon9 |
| 5162 | SP_5162_UI_UIWINDOWIMG_TITLE_BACKGRND2 | UI/UIWindow.img/Title/backgrnd2 |
| 5163 | SP_5163_UI_UIWINDOWIMG_TITLE_BACKGRND3 | UI/UIWindow.img/Title/backgrnd3 |
| 5164 | SP_5164_UI_UIWINDOWIMG_TITLE_BACKGRND4 | UI/UIWindow.img/Title/backgrnd4 |
| 5165 | SP_5165_UI_UIWINDOWIMG_TITLE_BACKGRND5 | UI/UIWindow.img/Title/backgrnd5 |
| 5166 | SP_5166_UI_UIWINDOWIMG_TITLE_BTGIVEUP | UI/UIWindow.img/Title/BtGiveup |
| 5167 | SP_5167_UI_UIWINDOWIMG_TITLE_BTOK | UI/UIWindow.img/Title/BtOK |
| 5168 | SP_5168_UI_UIWINDOWIMG_TITLE_ICON1 | UI/UIWindow.img/Title/icon1 |
| 5169 | SP_5169_UI_UIWINDOWIMG_TITLE_ICON2 | UI/UIWindow.img/Title/icon2 |
| 5170 | SP_5170_UI_UIWINDOWIMG_TITLE_TAB_DISABLED | UI/UIWindow.img/Title/Tab/disabled |
| 5171 | SP_5171_UI_UIWINDOWIMG_TITLE_TAB_ENABLED | UI/UIWindow.img/Title/Tab/enabled |
| 5172 | SP_5172_UI_UIWINDOWIMG_PERSONALSHOP_BACKGRND4 | UI/UIWindow.img/PersonalShop/backgrnd4 |
| 5173 | SP_5173_UI_UIWINDOWIMG_PERSONALSHOP_BACKGRND5 | UI/UIWindow.img/PersonalShop/backgrnd5 |
| 5174 | SP_5174_UI_UIWINDOWIMG_PERSONALSHOP_BACKGRND6 | UI/UIWindow.img/PersonalShop/backgrnd6 |
| 5175 | SP_5175_UI_UIWINDOWIMG_PERSONALSHOP_BTADD | UI/UIWindow.img/PersonalShop/BtAdd |
| 5176 | SP_5176_UI_UIWINDOWIMG_PERSONALSHOP_BTBLACKLIST | UI/UIWindow.img/PersonalShop/BtBlackList |
| 5177 | SP_5177_UI_UIWINDOWIMG_PERSONALSHOP_BTDELETE | UI/UIWindow.img/PersonalShop/BtDelete |
| 5178 | SP_5178_UI_UIWINDOWIMG_PERSONALSHOP_BTOK | UI/UIWindow.img/PersonalShop/BtOK |
| 5179 | SP_5179_UI_UIWINDOWIMG_PERSONALSHOP_BTSAVENAME | UI/UIWindow.img/PersonalShop/BtSaveName |
| 5180 | SP_5180_UI_UIWINDOWIMG_PERSONALSHOP_BTVISIT | UI/UIWindow.img/PersonalShop/BtVisit |
| 5181 | SP_5181_UI_UIWINDOWIMG_USERINFO_BTCOLLECTIONHIDE | UI/UIWindow.img/UserInfo/BtCollectionHide |
| 5182 | SP_5182_UI_UIWINDOWIMG_USERINFO_BTCOLLECTIONSHOW | UI/UIWindow.img/UserInfo/BtCollectionShow |
| 5183 | SP_5183_UI_UIWINDOWIMG_EQUIP_BACKGRND | UI/UIWindow.img/Equip/backgrnd |
| 5184 | SP_5184_UI_UIWINDOWIMG_EQUIP_FULLBACKGRND | UI/UIWindow.img/Equip/FullBackgrnd |
| 5185 | SP_5185_UI_UIWINDOWIMG_EQUIP_BTCASHSHOP | UI/UIWindow.img/Equip/BtCashshop |
| 5186 | SP_5186_UI_UIWINDOWIMG_EQUIP_BTDRAGONEQUIP | UI/UIWindow.img/Equip/BtDragonEquip |
| 5187 | SP_5187_HTTP__SNEXONCOM_MAPLESTORY_PAGE_GNXGUILDBOARDASPXURLGUILDBOARD_OPERATIONBOARD | http://%s.nexon.com/MapleStory/Page/Gnxguildboard.aspx?URL=GuildBoard/OperationBoard |
| 5188 | SP_5188_VERTICALIMPACT | verticalImpact |
| 5189 | SP_5189_VRANGE | vRange |
| 5190 | SP_5190_MAP_OBJ_ETCIMG_GOLDKEY_BACKGRND | Map/Obj/etc.img/goldkey/backgrnd |
| 5191 | SP_5191_MAP_OBJ_ETCIMG_GOLDKEY_NUMBER | Map/Obj/etc.img/goldkey/number |
| 5192 | SP_5192_MAP_OBJ_ETCIMG_GOLDKEY_KEY | Map/Obj/etc.img/goldkey/key |
| 5193 | SP_5193_SPIRITS_WHO_RECEIVE_CSS_ENERGY_WILL_GIVE_YOUR_CHARACTER_A_BOOST_1_SKILL_POINT_WI | Spirits who receive #c%s#'s energy will give your character a boost. 1 Skill Point will be granted each time #c%s#'s level increases by 10. |
| 5194 | SP_5194_THE_ITEMS_EFFECTS_WILL_DISAPPEAR_AFTER_THE_CSLOT_EXPIRATION_DATE_OF_DMONTH_DDATE | The item's effects will disappear after the #cslot expiration date of %d(month)-%d(date)-%04d(year) at %02d(hour):%02d(minute). |
| 5195 | SP_5195_THE_ITEM_WILL_BE_EQUIPPED_IF_YOU_PURCHASE_A_S_SLOT_EXTENDER_AT_THE_C_CASH_SHOP | The item will be equipped if you purchase a %s slot extender at the #c Cash Shop.# |
| 5196 | SP_5196_UI_UIWINDOWIMG_EQUIP_DRAGONEQUIP | UI/UIWindow.img/Equip/DragonEquip |
| 5197 | SP_5197_MOVEMENT_IS_POSSIBLE_ONLY_WITHIN_THE_ACCOUNT | Movement is possible only within the account |
| 5198 | SP_5198_ADMIN | Admin |
| 5199 | SP_5199_PLAYER_S_HAS_LOST_ALL_OF_HIS_HER_WOOL | Player %s has lost all of his/her wool. |
| 5200 | SP_5200_BAAAA | Baaaa~ |
| 5201 | SP_5201_BAA | Baa~ |
| 5202 | SP_5202_BAA_BAA | Baa Baa~ |
| 5203 | SP_5203_OWOO | Owoo~ |
| 5204 | SP_5204_WOOF_WOOF_WOOF | Woof Woof Woof! |
| 5205 | SP_5205_OWOOOO | Owoooo~ |
| 5206 | SP_5206_YOU_USED_THE_ITEM_BUT_NOTHING_HAPPENED | You used the item but nothing happened. |
| 5207 | SP_5207_SOME_ITEMS_CANNOT_BE_TRADED_WITH_OTHER_USERS_AND_R_N | Some items cannot be traded with other users and\r\n |
| 5208 | SP_5208_IF_YOU_PURCHASE_A_SDCASH_YOU_CAN_EQUIP__R_N_THE_ITEM_FOR_D_ADDITIONAL_DAYS__R_N_ | If you purchase a %s(%dcash), you can equip \r\n the item for %d additional days. \r\n Refunds are not availabe after the purchase. \r\n Do you still wish to purchase it? |
| 5209 | SP_5209_THE_TIME_LIMIT_FOR_THE__R_N_S_SLOT_HAS_BEEN_EXTENDED_TO_DMONTH_DDATE_DYEAR_AT_DH | The time limit for the \r\n %s slot has been extended to %d(month)-%d(date)-%d(year) at %d(hour):%d(minute). |
| 5210 | SP_5210_YOU_HAVE_ADDED_S_SLOTS | You have added %s slots. |
| 5211 | SP_5211_IF_YOU_PURCHASE_THIS_ITEM_YOU_WILL_BE_ABLE_TO_EQUIP_R_NADDITIONAL_EQUIPMENT_ITEM | If you purchase this item, you will be able to equip\r\nadditional equipment items. |
| 5212 | SP_5212_AVAILABLE_UNTIL_DMONTH_DDATE_04DYEAR_AT_02DHOUR02DMINUTE | Available until %d(month)-%d(date)-%04d(year) at %02d(hour):%02d(minute) |
| 5213 | SP_5213_IF_YOU_USE_THIS_ITEM_YOU_WILL_NOT_BE_ABLE_R_NTO_TRADE_THE_ITEM_WITH_OTHER_USERS_ | If you use this item, you will not be able\r\nto trade the item with other users.\r\nDo you really want to use this item? |
| 5214 | SP_5214_TIME_LIMIT_EXPIRED | Time Limit Expired |
| 5215 | SP_5215_HAVE_YOU_CHECKED_THE_R_NTIME_LIMIT_ON_THE_ITEM | Have you checked the\r\ntime limit on the item? |
| 5216 | SP_5216_DDAYS_DHOURS_AND_DMINUTES_LEFT | %ddays, %dhours, and %dminutes left |
| 5217 | SP_5217__GM__YOU_HAVE_RECEVIED_A_LETTER_FROM_AN_ADMIN_PLEASE_CLICK_ON_THE_ENVELOPE_AT_TH | [GM] You have recevied a letter from an Admin. Please click on the envelope at the top right. |
| 5218 | SP_5218__CASHTRADE | /CashTrade |
| 5219 | SP_5219__GAME_TRADE | /Game Trade |
| 5220 | SP_5220_THIS_IS_ONLY_AVAILABLE_WHILE_ABOARD_THE_S | This is only available while aboard the %s. |
| 5221 | SP_5221__ARTIFACT_RANKING | /Artifact Ranking |
| 5222 | SP_5222_DUE_TO_THE_EFFECT_OF_THE_TRESURE_BOX_YOU_WILL_RECEIVE_AN_ADDITIONAL_D_POINTS_FOR | Due to the effect of the Tresure Box, you will receive an additional %d%% points for %dseconds. |
| 5223 | SP_5223_YOU_CAN_ONLY_BE_EQUIPPED_WITH_1_UNIQUE_EQUIPPED_ITEM | You can only be equipped with 1 Unique Equipped Item. |
| 5224 | SP_5224_PLEASE_USE_TRADE | Please use 'Trade'. |
| 5225 | SP_5225_THIS_IS_A_CASH_TRADE_INVITE | This is a Cash Trade Invite |
| 5226 | SP_5226_SEPARATION_IS_NOT_POSSIBLE_DUE_TO_INSUFFICIENT_MESOS_R_NYOU_WILL_NEED_D_MESOS_TO | Separation is not possible due to insufficient Mesos.\r\nYou will need %d Mesos to\r\nseparate with a Senior. |
| 5227 | SP_5227_SEPARATION_IS_NOT_POSSIBLE_DUE_TO_INSUFFICIENT_MESOS_R_NYOU_WILL_NEED_D_MESOS_TO | Separation is not possible due to insufficient Mesos.\r\nYou will need %d Mesos to\r\nseparate with a Junior. |
| 5228 | SP_5228__R_N_R_NFEED_MESOS | \r\n\r\nFee:%d Mesos |
| 5229 | SP_5229_D_REPUTATION_POINTS_FOR_YOURSELF_AND_D_REPUTATION_POINTS_FOR_YOUR_SENIOR_WILL_BE | %d Reputation Points for yourself and %d Reputation Points for your Senior will be deducted\r\nand all lower Family members will be affected.\r\nDo you really want to separate from the selected character? |
| 5230 | SP_5230_D_REPUTATION_POINTS_WILL_BE_DEDUCTED_FROM_YOU__R_NAND_ALL_LOWER_FAMILY_MEMBERS_W | %d Reputation Points will be deducted from you, \r\nand all lower Family members will be affected.\r\nDo you really want to separate from the selected character? |
| 5231 | SP_5231_D_REPUTATION_POINTS_FOR_THE_SENIOR_AND_D_REPUTATION_POINTS_FOR_THE_2ND_SENIOR_WI | %d Reputation Points for the Senior and %d Reputation Points for the 2nd Senior will be deducted, and all lower Family members will be affected.\r\nDo you really want to separate from the selected character? |
| 5232 | SP_5232_D_REPUTATIONS_FOR_THE_SENIOR_WILL_BE_DEDUCTED_AND_R_NALL_LOWER_FAMILY_MEMBERS_WI | %d Reputations for the Senior will be deducted, and\r\nall lower Family members will be affected. Do you really want to separate from the selected character? |
| 5233 | SP_5233_THE_FAMILY_RELATION_HAS_BEEN_SEVERED_AS_S_HAS_INITIATED_A_SEPARATION | The family relation has been severed as %s has initiated a separation. |
| 5234 | SP_5234_FAMILY_RELATIONS_HAVE_BEEN_SEVERED_AS_A_FAMILY_MEMBER_HAS_INITIATED_SEPARATION_D | Family relations have been severed as a Family member has initiated separation. %d Reputation Points will be deducted. |
| 5235 | SP_5235_THE_FAMILY_RELATION_HAS_BEEN_SEVERED_AS_S_HAS_INITIATED_A_SEPERATION_REPUTATION_ | The family relation has been severed as %s has initiated a seperation. Reputation Points will be deducted by %d. |
| 5236 | SP_5236_02DHOUR02DMINUTE | %02d(hour)%02d(minute) |
| 5237 | SP_5237_MU_YOUNG_THAT_WAS_A_CLOSE_CALL_I_CANT_BELIEVE_YOU_TRIED_TO_FIGHT_BALROG_WHEN_YOU | Mu Young: That was a close call!! I can't believe you tried to fight Balrog when you are so weak… Draw Balrog's attention and continue hitting him for 10 minutes while I seal him up. |
| 5238 | SP_5238_MU_YOUNG_THAT_WAS_A_CLOSE_CALL_I_CANT_BELIEVE_YOU_TRIED_TO_FIGHT_BALROG_WHEN_YOU | Mu Young: That was a close call!! I can't believe you tried to fight Balrog when you are so weak… Draw Balrog's attention and continue hitting him for 5 minutes while I seal him up. |
| 5239 | SP_5239_A_PORTION_WAS_NOT_FOUND_AFTER_FALLING_ON_THE_GROUND | A portion was not found after falling on the ground. |
| 5240 | SP_5240_S_HAS_DISAPPEARED_AS_THE_TIME_LIMIT_HAS_PASSED | %s has disappeared as the time limit has passed. |
| 5241 | SP_5241_YOU_HAVE_USED_1_WHEEL_OF_DESTINY_IN_ORDER_TO_REVIVE_AT_THE_CURRENT_MAP_D_LEFT | You have used 1 Wheel of Destiny in order to revive at the current map. (%d left) |
| 5242 | SP_5242_TIME_LIMIT_EXTENSION_IS_NOT_POSSIBLE_ON_THIS_ITEM | Time limit extension is not possible on this item. |
| 5243 | SP_5243_FAILED_TO_CONNECT_TO_THE_UPDATE_SERVER_PLEASE_TRY_AGAIN_LATER_OR_CHECK_THE_STATU | Failed to connect to the update server. Please try again later or check the status of your network. |
| 5244 | SP_5244_HACKSHIELD_HAS_NOT_INITIALIZED_PLEASE_TRY_AGAIN_AFTER_REBOOTING_CLOSING_OTHER_PR | HackShield has not initialized. Please try again after rebooting, closing other programs, or running a virus or spyware check. |
| 5245 | SP_5245_THE_VIRUS_OR_HACKING_TOOL_DETECTION_MODULE_HAS_FAILED_TO_LOAD__YOU_MAY_HAVE_INSU | The virus or hacking tool detection module has failed to load.  You may have insufficient memory or your computer may be infected with a virus. |
| 5246 | SP_5246_HACKSHIELD_FILE_DOES_NOT_EXIST_OR_HAS_BEEN_MODIFIED | Hackshield file does not exist or has been modified. |
| 5247 | SP_5247_THE_GAME_IS_ALREADY_LOADED_OR_HACKSHIELD_IS_ALREADY_IN_USE_PLEASE_TRY_AGAIN_AFTE | The game is already loaded or HackShield is already in use. Please try again after ending the game. |
| 5248 | SP_5248_A_HACKING_PROGRAM_HAS_BEEN_FOUND_PLEASE_CLOSE_ANY_UNNECESSARY_PROGRAMS_AND_TRY_A | A hacking program has been found. Please close any unnecessary programs and try again. |
| 5249 | SP_5249_ACCESS_AUTHORITY_FOR_HACKSHIELD_FILES_IS_INVALID | Access authority for Hackshield files is invalid. |
| 5250 | SP_5250_THIS_IS_AN_ERROR_THAT_OCCURS_WHEN_THERE_IS_AN_UNKNOWN_PROBLEM | This is an error that occurs when there is an unknown problem. |
| 5251 | SP_5251_HACKSHIELD_INITIALIZATION_ERROR_0X08X_R_N_R_N | HackShield: Initialization Error (0x%08X)\r\n\r\n |
| 5252 | SP_5252_HACKSHIELD_HACKING_DETECTED_0X08X_R_N_R_NA_HACKING_ATTEMPT_HAS_BEEN_DETECTED_MAP | HackShield: Hacking Detected (0x%08X)\r\n\r\nA hacking attempt has been detected. MapleStory will now close. Please visit our website for more information. |
| 5253 | SP_5253_HACKSHIELD_UPDATE_ERROR_0X08X_R_N_R_N | HackShield: Update Error (0x%08X)\r\n\r\n |
| 5254 | SP_5254_LEGEND | Legend |
| 5255 | SP_5255_YOU_CANNOT_MOVE_ANYMORE_IN_THIS_AREA_R_NYOU_WILL_BE_DELETED_OFF_THE_LIST | You cannot move anymore in this area.\r\nYou will be deleted off the list. |
| 5256 | SP_5256_YOU_CANNOT_OPEN_A_STORE_IN_THIS_CHANNEL_PLEASE_USE_A_DIFFERENT_CHANNEL | You cannot open a store in this channel. Please use a different channel. |
| 5257 | SP_5257_MYKEYDEFENSE_INITIALIZATIN_ERROR_0X08X_R_N_R_N | MyKeyDefense: Initializatin Error (0x%08X)\r\n\r\n |
| 5258 | SP_5258_THIS_ITEM_CANNOT_BE_RECOVERED_ONCE_DROPPED_HOWEVER_IT_IS_POSSIBLE_TO_TRANSFER_IT | This item cannot be recovered once dropped. However, It is possible to transfer it to a different character on the same account by using the storage system. \r\n\r\nDo you really want to drop this item? |
| 5259 | SP_5259_IF_YOU_INVEST_YOUR_AP_IN_HP_OR_MP_YOUR_CHARACTER_MAY_HAVE_R_NINSUFFICIENT_STATS_ | If you invest your AP in HP or MP, your character may have\r\ninsufficient stats to become as strong as it could be.\r\nDo you still wish to raise this skill? |
| 5260 | SP_5260_SS_HAS_ADVANCED_TO_AAN_S | <%s%s has advanced to a(an) %s. |
| 5261 | SP_5261_SS_IS_NOW_MARRIED_PLEASE_CONGRATULATE_THEM | <%s%s is now married. Please congratulate them. |
| 5262 | SP_5262_COMPLETE | Complete |
| 5263 | SP_5263_INCOMPLETE | Incomplete |
| 5264 | SP_5264_CONGRATULATIONS_YOUVE_WON_A_REWARD_IN_THE_EXCAVATION_HUNT_WITH_D_POINTS_PLEASE_G | Congratulations. You've won a reward in the Excavation Hunt with %d points. Please go to the store to receive your gift. |
| 5265 | SP_5265_S_HAS_MADE_THE_ARTIFACT_HUNT_RANKINGS | %s has made the Artifact Hunt rankings. |
| 5266 | SP_5266_S_HAS_SURPASSED_S_IN_THE_ARTIFACT_HUNT_RANKINGS | %s has surpassed %s in the Artifact Hunt rankings. |
| 5267 | SP_5267_S___S | %s - %s |
| 5268 | SP_5268_THIS_WEEKS_ARTIFACT_HUNT_WINNER | This week's Artifact Hunt winner |
| 5269 | SP_5269_S_IS_CURRENTLY_RANKED_IN_THE_TOP_10_OF_THE_ARTIFACT_HUNT | %s is currently ranked in the Top 10 of the Artifact Hunt. |
| 5270 | SP_5270_CASH_ITEMS_BECOME_UNTRADABLE_R_NONCE_THEY_HAVE_BEEN_TRADED_AND__R_NMAPLE_POINTS_ | Cash Items become untradable\r\nonce they have been traded and \r\nMaple Points cannot be accumulated\r\nusing Item Deletions in the Cash Shop.\r\n\r\nDo you really want to buy? |
| 5271 | SP_5271_A_CASH_ITEM_PURCHASE_WAS_CANCELED_R_NDUE_TO_A_SERVER_CHECK | A cash item purchase was canceled\r\ndue to a server check. |
| 5272 | SP_5272_PURCHASE_WAS_CANCELED_BECAUSE_THERE_WAS_AN_ITEM_R_NTHAT_DISAPPEARED_BECAUSE_IT_E | Purchase was canceled because there was an item\r\nthat disappeared because it expired. |
| 5273 | SP_5273_SEAL_RELEASED_ON_D_D_04D_02D02D | Seal Released on %d-%d-%04d %02d:%02d |
| 5274 | SP_5274_TIME_LIMIT_EXTENSION_IS_NOT_POSSIBLE | Time limit extension is not possible. |
| 5275 | SP_5275_UNIQUE_EQUIPPED_ITEM | Unique Equipped Item |
| 5276 | SP_5276_YOU_HAVE_RECEIVED_A_LETTER_FROM_AN_ADMIN_R_NPLEASE_CLICK_ON_THE_ENVELOPE | You have received a letter from an Admin.\r\nPlease click on the envelope. |
| 5277 | SP_5277_CASH_ITEMS_BECOME_UNTRADABLE_R_NONCE_THEY_HAVE_BEEN_TRADED_AND__R_NMAPLE_POINTS_ | Cash Items become untradable\r\nonce they have been traded and \r\nMaple Points cannot be accumulated\r\nusing Item Deletions in the Cash Shop.\r\n\r\nDo you really want to trade? |
| 5278 | SP_5278_A_CASH_ITEM_TRADE_WAS_CANCELED_R_NDUE_TO_A_SERVER_CHECK | A cash item trade was canceled\r\ndue to a server check. |
| 5279 | SP_5279_TRADE_WAS_CANCELED_BECAUSE_THERE_WAS_AN_ITEM_R_NTHAT_DISAPPEARED_BECAUSE_IT_EXPI | Trade was canceled because there was an item\r\nthat disappeared because it expired. |
| 5280 | SP_5280_HOW_MANY_WILL_YOU_DROP_A_PORTION_OF_THE_AMOUNT_MAY_BE_LOST_IF_PICKED_UP_BY_ANOTH | How many will you drop? (A portion of the amount may be lost if picked up by another user.) |
| 5281 | SP_5281_YOU_CAN_RAISE_THE_DESIRED_STAT_BY_PRESSING_THE_ARROW_KEY | You can raise the desired stat by pressing the arrow key. |
| 5282 | SP_5282_THE_MOST_IMPORTANT_STAT_FOR_A_WARRIOR_IS_STR_AND | The most important stat for a Warrior is STR and |
| 5283 | SP_5283_THE_MOST_IMPORTANT_STAT_FOR_A_MAGICIAN_IS_AND | The most important stat for a Magician is and |
| 5284 | SP_5284_IN_ADDITION_A_BIT_OF_LUK_IS_REQUIRED | In addition, A bit of LUK is required. |
| 5285 | SP_5285_THE_MOST_IMPORTANT_STAT_FOR_A_BOWMAN_IS_DEX_AND | The most important stat for a Bowman is DEX and |
| 5286 | SP_5286_IN_ADDITION_A_BIT_OF_STR_IS_REQUIRED | In addition, A bit of STR is required. |
| 5287 | SP_5287_THE_MOST_IMPORTANT_STAT_FOR_A_THIEF_IS_LUK_AND | The most important stat for a Thief is LUK and |
| 5288 | SP_5288_THE_MOST_IMPORTANT_STAT_FOR_A_KNUCKLE_USING_PIRATE_IS_STR_AND | The most important stat for a knuckle using Pirate is STR and |
| 5289 | SP_5289_IN_ADDITION_A_BIT_OF_DEX_IS_REQUIRED | In addition, a bit of DEX is required. |
| 5290 | SP_5290_THE_MOST_IMPORTANT_STAT_FOR_A_GUN_USING_PIRATE_IS_DEX_AND | The most important stat for a gun using Pirate is DEX and |
| 5291 | SP_5291_IN_ADDITION_A_BIT_OF_STR_IS_REQUIRED | In addition, a bit of STR is required. |
| 5292 | SP_5292_THE_MOST_IMPORTANT_STAT_FOR_A_DAWN_WARRIOR_IS_STR_AND | The most important stat for a Dawn Warrior is STR and |
| 5293 | SP_5293_THE_MOST_IMPORTANT_STAT_FOR_A_BLAZE_WIZARD_IS_INT_AND | The most important stat for a Blaze Wizard is INT and |
| 5294 | SP_5294_IN_ADDITION_A_BIT_OF_LUK_IS_REQUIRED | In addition, a bit of LUK is required. |
| 5295 | SP_5295_THE_MOST_IMPORTANT_STAT_FOR_A_WIND_ARCHER_IS_DEX_AND | The most important stat for a Wind Archer is DEX and |
| 5296 | SP_5296_THE_MOST_IMPORTANT_STAT_FOR_A_NIGHT_WALKER_IS_LUK_AND | The most important stat for a Night Walker is LUK and |
| 5297 | SP_5297_THE_MOST_IMPORTANT_STAT_FOR_A_THUNDER_BREAKER_IS_STR_AND | The most important stat for a Thunder Breaker is STR and |
| 5298 | SP_5298_IF_YOU_PRESS_THE_AUTOMATIC_DISTRIBUTION_BUTTON_THE_AP_WILL_BE | If you press the automatic distribution button, the AP will be |
| 5299 | SP_5299_DISTRIBUTED_AUTOMATICALLY_AS_NEEDED | distributed automatically as needed. |
| 5300 | SP_5300_NUMBER_OF_MEDALS_OBTAINED_D | Number of medals obtained: %d |
| 5301 | SP_5301_OBTAINABLE_TITLE | Obtainable Title |
| 5302 | SP_5302_TITLE_BEING_CHALLENGED | Title being challenged |
| 5303 | SP_5303_IF_YOU_PURCHASE_A_S_SLOT_ADDTION_IN_THE_CASH_SHOP_YOU_CAN_ADDITIONALLY_EQUIP_A_S | If you purchase a %s slot addtion in the Cash Shop, you can additionally equip a %s. |
| 5304 | SP_5304_ITEM_INSTALL_04DIMG_08D_EFFECT2 | Item/Install/%04d.img/%08d/effect2 |
| 5305 | SP_5305_INCLEV | incLEV |
| 5306 | SP_5306__HELP | /Help |
| 5307 | SP_5307__S_S_BROADCASTING_WILL_BEGIN_PLEASE_TURN_UP_THE_VOLUME | [%s]'s broadcasting will begin. Please turn up the volume. |
| 5308 | SP_5308__S_S_BROADCASTING_HAS_ENDED | [%s]'s broadcasting has ended. |
| 5309 | SP_5309__R_NTHE_ITEM_YOU_PURCHASE_WITH_NX_CAN_BE_TRADED_ONCE_WITH_ANOTHER_USER_PRIOR_TO_ | \r\n#The item you purchase with NX can be traded once with another user prior to use.# |
| 5310 | SP_5310__R_NYOU_CANNOT_TRADE_A_USED_ITEM_WITH_ANOTHER_PLAYER | \r\n#You cannot trade a used item with another player.# |
| 5311 | SP_5311_YOU_CANNOT_ACQUIRE_ANY_ITEMS_BECAUSE_THE_GAME_FILE_HAS_BEEN_DAMAGED_PLEASE_TRY_A | You cannot acquire any items because the game file has been damaged. Please try again after reinstalling the game. |
| 5312 | SP_5312_THE_FORCE_OF_THE_UNDERGROUND_HAS_PUSHED_YOU_OUT | The force of the underground has pushed you out. |
| 5313 | SP_5313_CHARACTERS_WHOSE_LEVEL_IS_BELOW_LV_10_SUCH_AS_BEGINNERS_NOBLESSES_AND_LEGENDS_CA | Characters whose level is below Lv. 10, such as Beginners, Noblesses, and Legends cannot form a party. |
| 5314 | SP_5314_ADVENTURER | Adventurer |
| 5315 | SP_5315_AN_ILLEGAL_PROGRAM_HAS_BEEN_DETECTED_R_NPLEASE_TRY_AGAIN_AFTER_REINSTALLING_THE_ | An illegal program has been detected.\r\nPlease try again after reinstalling the game.\r\n\r\nIllegal program usage could result in banning from the game. |
| 5316 | SP_5316_PRESS_1_2_OR_3_TO_CHANGE_THE_GROUP_YOUD_LIKE_TO_TALK_TO | press 1, 2, or 3 to change the group you'd like to talk to. |
| 5317 | SP_5317_YOU_CANNOT_ACQUIRE_ANY_ITEMS | You cannot acquire any items. |
| 5318 | SP_5318_YOU_CANNOT_USE_THIS_ITEM_R_NBECAUSE_A_SIMILAR_EVENT_IS_IN_PROGRESS | You cannot use this item\r\nbecause a similar event is in progress. |
| 5319 | SP_5319_THIS_ITEM_HAS_EXPIRED | This item has expired. |
| 5320 | SP_5320_NOT_AVAILABLE_FOR_BEGINNERS_NOBLESSES_AND_LEGENDS | Not available for Beginners, Noblesses, and Legends. |
| 5321 | SP_5321_YOU_GOT_A_TITLE | You got a title! |
| 5322 | SP_5322_CHARLEVEL | CharLevel |
| 5323 | SP_5323_COMBOFENRIR | comboFenrir |
| 5324 | SP_5324_COMBOSMASH | comboSmash |
| 5325 | SP_5325_COMBOTEMPEST | comboTempest |
| 5326 | SP_5326_DOUBLESWING | doubleSwing |
| 5327 | SP_5327_EFFECT_BASICEFFIMG_COOLHIT_COOL | Effect/BasicEff.img/CoolHit/cool |
| 5328 | SP_5328_EFFECT_BASICEFFIMG_GETPARAO | Effect/BasicEff.img/GetParao |
| 5329 | SP_5329_UI_UIWINDOWIMG_ITEM_NEW_TAB1 | UI/UIWindow.img/Item/New/Tab1 |
| 5330 | SP_5330_UI_UIWINDOWIMG_ITEM_NEW_TAB0 | UI/UIWindow.img/Item/New/Tab0 |
| 5331 | SP_5331_UI_UIWINDOWIMG_ITEM_NEW_INVENTORY | UI/UIWindow.img/Item/New/inventory |
| 5332 | SP_5332_FINALBLOW | finalBlow |
| 5333 | SP_5333_FINALCHARGE | finalCharge |
| 5334 | SP_5334_FINALTOSS | finalToss |
| 5335 | SP_5335_HTTP__SNEXONCOM_MAPLESTORY_PAGE_GNXGAMEASPXURLEVENT_200909_LOGINAUTHKEYS | http://%s.nexon.com/MapleStory/Page/Gnxgame.aspx?URL=Event/200909/LoginAuth&key=%s |
| 5336 | SP_5336_KILLING_CLEAR | killing/clear |
| 5337 | SP_5337_KILLING_FAIL | killing/fail |
| 5338 | SP_5338_MAP_OBJ_ETCIMG_KILLING_BACKGRND | Map/Obj/etc.img/killing/backgrnd |
| 5339 | SP_5339_MAP_OBJ_ETCIMG_KILLING_FONTTIME | Map/Obj/etc.img/killing/fontTime |
| 5340 | SP_5340_MAP_WORLDMAP_WORLDMAP03DIMG | Map/WorldMap/WorldMap%03d.img |
| 5341 | SP_5341_MASSACRECOOL | massacre_cool |
| 5342 | SP_5342_MASSACREHIT | massacre_hit |
| 5343 | SP_5343_MASSACRELASTSTAGE | massacre_laststage |
| 5344 | SP_5344_MASSACREMISS | massacre_miss |
| 5345 | SP_5345_MASSACREPARTY | massacre_party |
| 5346 | SP_5346_MASSACRESKILL | massacre_skill |
| 5347 | SP_5347_MINUS | minus |
| 5348 | SP_5348_MISS | miss |
| 5349 | SP_5349_MOBCODE | mobCode |
| 5350 | SP_5350_PLUS | plus |
| 5351 | SP_5351_PYRAMID | pyramid |
| 5352 | SP_5352_ROLLINGSPIN | rollingSpin |
| 5353 | SP_5353_SELECTEDSKILLID | selectedSkillID |
| 5354 | SP_5354_SHOWLAYERTAG | showLayerTag |
| 5355 | SP_5355_SKILL_03DIMG_SKILL_07D_CHARLEVEL_D_EFFECT | Skill/%03d.img/skill/%07d/CharLevel/%d/effect |
| 5356 | SP_5356_SKILL_03DIMG_SKILL_07D_CHARLEVEL_D_HIT_D | Skill/%03d.img/skill/%07d/CharLevel/%d/hit/%d |
| 5357 | SP_5357_SOUND_RADIOIMG_S | Sound/Radio.img/%s |
| 5358 | SP_5358_SOUND_RADIOIMG_S_TRACK | Sound/Radio.img/%s/track |
| 5359 | SP_5359_TAGS | tags |
| 5360 | SP_5360_TRIPLESWING | tripleSwing |
| 5361 | SP_5361_UI_CASHSHOPIMG_BASE_BACKGRND2 | UI/CashShop.img/Base/backgrnd2 |
| 5362 | SP_5362_UI_LOGINIMG_WORLDSELECT_ALERT_BACKGRD | UI/Login.img/WorldSelect/alert/backgrd |
| 5363 | SP_5363_UI_LOGINIMG_WORLDSELECT_ALERT_BTARROWL | UI/Login.img/WorldSelect/alert/BtArrowL |
| 5364 | SP_5364_UI_LOGINIMG_WORLDSELECT_ALERT_BTARROWR | UI/Login.img/WorldSelect/alert/BtArrowR |
| 5365 | SP_5365_UI_LOGINIMG_WORLDSELECT_ALERT_BTCHOICE | UI/Login.img/WorldSelect/alert/BtChoice |
| 5366 | SP_5366_UI_LOGINIMG_WORLDSELECT_ALERT_BTCLOSE | UI/Login.img/WorldSelect/alert/BtClose |
| 5367 | SP_5367_UI_LOGINIMG_WORLDSELECT_BTVIEWALL | UI/Login.img/WorldSelect/BtViewAll |
| 5368 | SP_5368_UI_LOGINIMG_WORLDSELECT_BTVIEWCHOICE | UI/Login.img/WorldSelect/BtViewChoice |
| 5369 | SP_5369_UI_UIWINDOWIMG_MAPLEEVENT_BACKGRND | UI/UIWindow.img/MapleEvent/backgrnd |
| 5370 | SP_5370_UI_UIWINDOWIMG_MAPLEEVENT_LOADING | UI/UIWindow.img/MapleEvent/Loading |
| 5371 | SP_5371_UI_UIWINDOWIMG_MONSTERKILLING_COUNT_BACKGRD0 | UI/UIWindow.img/MonsterKilling/Count/backgrd0 |
| 5372 | SP_5372_UI_UIWINDOWIMG_MONSTERKILLING_COUNT_BACKGRD1 | UI/UIWindow.img/MonsterKilling/Count/backgrd1 |
| 5373 | SP_5373_UI_UIWINDOWIMG_MONSTERKILLING_COUNT_KEYBACKGRD_CLOSE | UI/UIWindow.img/MonsterKilling/Count/keyBackgrd/close |
| 5374 | SP_5374_UI_UIWINDOWIMG_MONSTERKILLING_COUNT_KEYBACKGRD_ING | UI/UIWindow.img/MonsterKilling/Count/keyBackgrd/ing |
| 5375 | SP_5375_UI_UIWINDOWIMG_MONSTERKILLING_COUNT_KEYBACKGRD_OPEN | UI/UIWindow.img/MonsterKilling/Count/keyBackgrd/open |
| 5376 | SP_5376_UI_UIWINDOWIMG_MONSTERKILLING_COUNT_NUMBER | UI/UIWindow.img/MonsterKilling/Count/number |
| 5377 | SP_5377_UI_UIWINDOWIMG_MONSTERKILLING_COUNT_NUMBER2 | UI/UIWindow.img/MonsterKilling/Count/number2 |
| 5378 | SP_5378_UI_UIWINDOWIMG_MONSTERKILLING_GAUGE_BACKGRD | UI/UIWindow.img/MonsterKilling/Gauge/backgrd |
| 5379 | SP_5379_UI_UIWINDOWIMG_MONSTERKILLING_GAUGE_BACKGRDD | UI/UIWindow.img/MonsterKilling/Gauge/backgrdD |
| 5380 | SP_5380_UI_UIWINDOWIMG_MONSTERKILLING_GAUGE_DANGER | UI/UIWindow.img/MonsterKilling/Gauge/danger |
| 5381 | SP_5381_UI_UIWINDOWIMG_MONSTERKILLING_GAUGE_ICOND | UI/UIWindow.img/MonsterKilling/Gauge/iconD |
| 5382 | SP_5382_UI_UIWINDOWIMG_MONSTERKILLING_GAUGE_PIXEL | UI/UIWindow.img/MonsterKilling/Gauge/pixel |
| 5383 | SP_5383_UI_UIWINDOWIMG_MONSTERKILLING_GAUGE_TEXT | UI/UIWindow.img/MonsterKilling/Gauge/text |
| 5384 | SP_5384_UI_UIWINDOWIMG_MONSTERKILLING_GAUGE_TEXTD | UI/UIWindow.img/MonsterKilling/Gauge/textD |
| 5385 | SP_5385_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_BACKGRD | UI/UIWindow.img/MonsterKilling/Result/backgrd |
| 5386 | SP_5386_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_BACKGRD2 | UI/UIWindow.img/MonsterKilling/Result/backgrd2 |
| 5387 | SP_5387_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_NUMBER | UI/UIWindow.img/MonsterKilling/Result/number |
| 5388 | SP_5388_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_NUMBER2 | UI/UIWindow.img/MonsterKilling/Result/number2 |
| 5389 | SP_5389_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_RANK_A | UI/UIWindow.img/MonsterKilling/Result/Rank/a |
| 5390 | SP_5390_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_RANK_B | UI/UIWindow.img/MonsterKilling/Result/Rank/b |
| 5391 | SP_5391_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_RANK_C | UI/UIWindow.img/MonsterKilling/Result/Rank/c |
| 5392 | SP_5392_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_RANK_D | UI/UIWindow.img/MonsterKilling/Result/Rank/d |
| 5393 | SP_5393_UI_UIWINDOWIMG_MONSTERKILLING_RESULT_RANK_S | UI/UIWindow.img/MonsterKilling/Result/Rank/s |
| 5394 | SP_5394_UI_UIWINDOWIMG_SKILLUP_BACKGRD | UI/UIWindow.img/SkillUp/backgrd |
| 5395 | SP_5395_UI_UIWINDOWIMG_SKILLUP_BTOK | UI/UIWindow.img/SkillUp/btOK |
| 5396 | SP_5396_UI_UIWINDOWIMG_VEGASPELL_BACKGRND10 | UI/UIWindow.img/VegaSpell/backgrnd10 |
| 5397 | SP_5397_UI_UIWINDOWIMG_VEGASPELL_BACKGRND60 | UI/UIWindow.img/VegaSpell/backgrnd60 |
| 5398 | SP_5398_UI_UIWINDOWIMG_VEGASPELL_SUCCESSWND | UI/UIWindow.img/VegaSpell/SuccessWnd |
| 5399 | SP_5399_UI_UIWINDOWIMG_VEGASPELL_FAILWND | UI/UIWindow.img/VegaSpell/FailWnd |
| 5400 | SP_5400_UI_UIWINDOWIMG_VEGASPELL_BTSTART | UI/UIWindow.img/VegaSpell/BtStart |
| 5401 | SP_5401_UI_UIWINDOWIMG_VEGASPELL_BTOK | UI/UIWindow.img/VegaSpell/BtOK |
| 5402 | SP_5402_THIS_SCROLL_CANNOT_BE_USED | This scroll cannot be used. |
| 5403 | SP_5403_SOMETHING_WRONG | Something wrong! |
| 5404 | SP_5404_THE_ITEM_AND_THE_SCROLL_CANNOT_BE_COMBINED | The item and the scroll cannot be combined. |
| 5405 | SP_5405_UNKNOWUN_ERROR_D | Unknowun error %d. |
| 5406 | SP_5406_NO_VEGA_LIST | No Vega List |
| 5407 | SP_5407_VEGASUCCESS | VegaSuccess |
| 5408 | SP_5408_VEGAFAIL | VegaFail |
| 5409 | SP_5409_VEGATWINKLING | VegaTwinkling |
| 5410 | SP_5410_ETC_VEGASPELLIMG | Etc/VegaSpell.img |
| 5411 | SP_5411_UI_UIWINDOWIMG_VEGASPELL_COUNT_D | UI/UIWindow.img/VegaSpell/Count/%d |
| 5412 | SP_5412_USERINTERACT | userInteract |
| 5413 | SP_5413_FAKEQUESTID | fakeQuestID |
| 5414 | SP_5414__FORBIDDENMAPRELOADD | /forbiddenmapreloadd |
| 5415 | SP_5415_YOU_HAVE_ACQUIRED_D_GACHAPON_STAMPS_R_NBY_PURCHASING_THE_GACHAPON_TICKET | You have acquired %d Gachapon Stamps\r\nby purchasing the Gachapon Ticket. |
| 5416 | SP_5416_YOU_CANNOT_RECEIVE_ANY_GACHAPON_STAMPS_R_NBECAUSE_YOU_DO_NOT_HAVE_ENOUGH_ROOM_IN | You cannot receive any Gachapon Stamps\r\nbecause you do not have enough room in\r\nyour Etc window. |
| 5417 | SP_5417_PURCHASE_WITH_D_POINTS_AND_GET_D_STAMPS_R_NTHE_FOLLOWING_STAMP_CAN_BE_RECEIVED_W | Purchase with %d points and get %d Stamps!\r\nThe following Stamp can be received with %d points.\r\nAccumulated %d points; total number acquired %d.\r\nIf you exit the Cash Shop\r\nthe points you have accumulated will be reset. |
| 5418 | SP_5418_USERINFOSCALE | userInfoScale |
| 5419 | SP_5419_USERINFODIFFX | userInfoDiffX |
| 5420 | SP_5420_USERINFODIFFY | userInfoDiffY |
| 5421 | SP_5421_EVAN | Evan |
| 5422 | SP_5422_EVAN0 | Evan0 |
| 5423 | SP_5423_EVAN1 | Evan1 |
| 5424 | SP_5424_EVAN2 | Evan2 |
| 5425 | SP_5425_EVAN3 | Evan3 |
| 5426 | SP_5426_EVAN4 | Evan4 |
| 5427 | SP_5427_EVAN5 | Evan5 |
| 5428 | SP_5428_EVAN6 | Evan6 |
| 5429 | SP_5429_EVAN7 | Evan7 |
| 5430 | SP_5430_EVAN8 | Evan8 |
| 5431 | SP_5431_EVAN9 | Evan9 |
| 5432 | SP_5432_EVAN10 | Evan10 |
| 5433 | SP_5433_YOU_NEED_D_MORE_SS | You need %d more %s(s) |
| 5434 | SP_5434_YOU_NEED_MORE_ITEMS | You need more items |
| 5435 | SP_5435_IF_YOU_DO_NOT_USE_YOUR_AP_WHEN_YOU_LEVEL_UP__R_NTHEN_YOU_WONT_BE_ABLE_TO_RECEIVE | If you do not use your AP when you level up, \r\nthen you won't be able to receive any additional AP.\r\nPlease use your AP. |
| 5436 | SP_5436_RAINBOW_WEEK_BONUS_EXP_D | Rainbow Week Bonus EXP (+%d) |
| 5437 | SP_5437_YOU_HAVE_GAINED_ITEMS_IN_THE_S_TAB_S_D | You have gained items in the %s tab (%s %d) |
| 5438 | SP_5438_YOU_HAVE_GAINED_AN_ITEM_IN_THE_S_TAB_S | You have gained an item in the %s tab (%s) |
| 5439 | SP_5439_WITH_THE_WEAPON_THAT_S_CURRENTLY_HAS_EQUIPPED_R_NTHIS_ITEM_CANT_BE_USED_R_NDO_YO | With the weapon that %s currently has equipped,\r\nthis item can't be used.\r\nDo you still wish to give it as a gift? |
| 5440 | SP_5440_THE_PET_THAT_S_IS_CURRENTLY_RAISING_R_NCANNOT_BE_EQUIPPED_WITH_THIS_ITEM_R_NDO_Y | The pet that %s is currently raising\r\ncannot be equipped with this item.\r\nDo you still wish to give it as a gift? |
| 5441 | SP_5441_THIS_ITEM_CAN_ONLY_BE_EQUIPPED_ON_PETS_R_NDO_YOU_STILL_WISH_TO_GIVE_IT_AS_A_GIFT | This item can only be equipped on pets.\r\nDo you still wish to give it as a gift? |
| 5442 | SP_5442_THIS_ITEM_CANNOT_BE_USED_WITHOUT_A_PET_R_NNOR_CAN_IT_BE_USED_ON_A_PET_THATS_NO_L | This item cannot be used without a pet\r\nnor can it be used on a pet that's no longer living.\r\nDo you really want to give it as a gift? |
| 5443 | SP_5443_WITH_THE_PET_THAT_S_CURRENTLY_HAS_EQUIPPED_R_NTHIS_ITEM_CANT_BE_USED_R_NDO_YOU_S | With the pet that %s currently has equipped,\r\nthis item can't be used.\r\nDo you still wish to give it as a gift? |
| 5444 | SP_5444_WITHOUT_A_MONSTER_MOUNT_SKILL_R_NTHIS_ITEM_CANT_BE_USED_R_NDO_YOU_STILL_WISH_TO_ | Without a Monster Mount skill,\r\nthis item can't be used.\r\nDo you still wish to give it as a gift? |
| 5445 | SP_5445_CAN_BE_USED_FOR_S_AFTER_PURCHASE | Can be used for %s after purchase |
| 5446 | SP_5446__DDAYS |  %dDay(s) |
| 5447 | SP_5447__DHOURS |  %dHour(s) |
| 5448 | SP_5448__DMINUTES |  %dMinute(s) |
| 5449 | SP_5449_YOU_MUST_BE_OVER_LVD_TO_PURCHASE_THIS_ITEM | You must be over lv.%d to purchase this item |
| 5450 | SP_5450_YOU_MUST_BE_UNDER_LVD_TO_PURCHASE_THIS_ITEM | You must be under lv.%d to purchase this item |
| 5451 | SP_5451_THIS_IS_A_TIME_RESTRICTED_ITEM_R_N | This is a time-restricted item\r\n |
| 5452 | SP_5452_ARE_YOU_SURE_YOU_WANT_TO_PURCHASE_R_NYOU_CURRENTLY_HAVE_D_S | Are you sure you want to purchase?\r\nYou currently have %d %s |
| 5453 | SP_5453_SHOULD_BE_LEFT_BLANK_IN_ENGLISH | (Should be left blank in English) |
| 5454 | SP_5454_THIS_ITEM_CAN_ONLY_BE_EQUIPPED_ON_EVAN | This item can only be equipped on Evan. |
| 5455 | SP_5455_EWELCOME_TO_THE_TEST_WORLDN_R_NTHIS_IS_A_PLACE_THAT_HAS_BEEN_CREATED_IN_ORDER_TO | #eWelcome to the Test World#n\r\nThis is a place that has been created in order to test\r\ncontent that is planned to be revised or added in the real MapleStory World.\r\nAs such, the following limitations apply.\r\n\r\n#b1. In the Test World, character development has been\r\nset on "easy" for the sake of testing. This info \r\nwill not be applied in the real MapleStory World.\r\n2. Character information from the Test World will never\r\nbe shared with the real MapleStory World. \r\n3. Character information in the Test World can be reset\r\nwithout any notice or agreement from the user.\r\n4. The Test World service may be stopped without\r\nany notice.\r\n5. Content tested here may not be\r\napplied to the real MapleStory World.\r\n6. When the tested contents are added to the real MapleStory World, there may be\r\nmodifications made to the stats.\r\n |
| 5456 | SP_5456_THE_KEY_YOU_JUST_PRESSED_IS_A_KEY_THAT_HAS_BEEN_DISABLED | The key you just pressed is a key that has been disabled. |
| 5457 | SP_5457_THIS_KEY_IS_ALREADY_BEING_USED | This key is already being used. |
| 5458 | SP_5458_THE_CHANGES_YOUVE_MADE_TO_THE_QUICK_SLOT_WILL_ALSO_BE_CANCELED | The changes you've made to the Quick Slot will also be canceled. |
| 5459 | SP_5459_THE_CHANGES_YOUVE_MADE_TO_THE_QUICK_SLOTS_WILL_ALSO_CHANGE_TO_DEFAULT | The changes you've made to the Quick Slots will also change to default. |
| 5460 | SP_5460_YOU_HAVE_REVIVED_ON_THE_CURRENT_MAP_THROUGH_THE_EFFECT_OF_THE_SPIRIT_STONE | You have revived on the current map through the effect of the Spirit Stone. |
| 5461 | SP_5461_THE_ENTITLEMENT_DOES_NOT_APPLY_BECAUSE_YOUR_LEVEL_DOES_NOT_MATCH_THE_CORRESPONDI | The Entitlement does not apply because your level does not match the corresponding area. |
| 5462 | SP_5462_THIS_MAP_CAN_ONLY_BE_ENTERED_BY_PARTY_MEMBERS | This map can only be entered by party members. |
| 5463 | SP_5463_YOU_HAVE_BEEN_KICKED_OUT_OF_THE_MAP_BECAUSE_YOU_LEFT_THE_PARTY | You have been kicked out of the map because you left the party. |
| 5464 | SP_5464_YOUR_CURRENT_CONDITION_WILL_NOT_PERMIT_YOU_TO_USE_A_POTION | Your current condition will not permit you to use a potion. |
| 5465 | SP_5465_YOU_WILL_RECEIVE_DAMAGE_IF_YOU_BECOME_COVERED_WITH_THE_SHADOW_OF_DARKNESS_AND_CA | You will receive damage if you become covered with the Shadow of Darkness and cannot move. |
| 5466 | SP_5466_THE_FEAR_OF_DARKNESS_HAS_OVERTAKEN_YOU_YOUR_RANGE_OF_VISION_HAS_BEEN_NARROWED | The fear of darkness has overtaken you! Your range of vision has been narrowed. |
| 5467 | SP_5467_DRAGON_HAT | Dragon Hat |
| 5468 | SP_5468_DRAGON_PENDANT | Dragon Pendant |
| 5469 | SP_5469_DRAGON_WING_ACCESSORY | Dragon Wing Accessory |
| 5470 | SP_5470_DRAGON_TAIL_ACCESSORY | Dragon Tail Accessory |
| 5471 | SP_5471_THIS_CONTAINS_AN_ITEM_THAT_WILL_DISAPPEAR_ONCE__R_NTHE_EXPIRATION_DATE_HAS_PASSE | This contains an item that will disappear once \r\nthe expiration date has passed. Do you want to give it as a gift? |
| 5472 | SP_5472_S_DOES_NOT_HAVE_AN_ITEM_THAT__R_NTHE_SCISSORS_OF_KARMA_CAN_BE_USED_ON_R_NDO_YOU_ | %s does not have an item that \r\nthe Scissors of Karma can be used on.\r\nDo you still wish to give it as a gift? |
| 5473 | SP_5473_YOU_RECEIVE_DAMAGE_HAVING_BEEN_COVERED_BY_THE_SHADOW_OF_DARKNESS | You receive damage having been covered by the Shadow of Darkness. |
| 5474 | SP_5474_SVRETURNMAP | svReturnMap |
| 5475 | SP_5475_PRAIDELAPSSEDTIMEATBOSS | PRaid_ElapssedTimeAtBoss |
| 5476 | SP_5476_PRAIDTEAM | PRaid_Team |
| 5477 | SP_5477_REDTEAM | redTeam |
| 5478 | SP_5478_BLUETEAM | blueTeam |
| 5479 | SP_5479_REDTEAMDAMAGE | redTeamDamage |
| 5480 | SP_5480_BLUETEAMDAMAGE | blueTeamDamage |
| 5481 | SP_5481_REDSTAGE | Red_Stage |
| 5482 | SP_5482_BLUESTAGE | Blue_Stage |
| 5483 | SP_5483_PRAIDBONUS | PRaid_Bonus |
| 5484 | SP_5484_PRAIDTOTAL | PRaid_Total |
| 5485 | SP_5485_RED_TEAM | Red Team |
| 5486 | SP_5486_BLUE_TEAM | Blue Team |
| 5487 | SP_5487_ONE_OF_YOUR_PARTY_MEMBERS_HAS_BEEN_BANISHED_FROM_THE_ROOM_DUE_TO_ABNORMAL_TERMIN | One of your party members has been banished from the room due to abnormal termination. |
| 5488 | SP_5488__R_NCEXPIRATION_DATE_DYEAR_DMONTH_DDAY_DHOUR_DMINUTE | \r\n#cExpiration Date: %dYear %dMonth %dDay %dHour %dMinute# |
| 5489 | SP_5489_THIS_ITEM_CANNOT_BE_USED_IN_THIS_MAP | This item cannot be used in this map. |
| 5490 | SP_5490_MAGICMISSILE | magicmissile |
| 5491 | SP_5491_DRAGONSPARK | dragonSpark |
| 5492 | SP_5492_DRAGONICEBREATHE | dragonIceBreathe |
| 5493 | SP_5493_DRAGONBREATHE | dragonBreathe |
| 5494 | SP_5494_ICEBREATHEPREPARE | icebreathe_prepare |
| 5495 | SP_5495_BREATHEPREPARE | breathe_prepare |
| 5496 | SP_5496_INFINITYEXPLOSION | infinityExplosion |
| 5497 | SP_5497_SUPERMAGICMISSILE | superMagicmissile |
| 5498 | SP_5498_ILLUSION | illusion |
| 5499 | SP_5499_MAGICFLARE | magicFlare |
| 5500 | SP_5500_MAGICBOOSTER | magicBooster |
| 5501 | SP_5501_DRAGONSHIELD | dragonShield |
| 5502 | SP_5502_DRAGONFURY | dragonFury |
| 5503 | SP_5503_DRAGONFLY | dragonFly |
| 5504 | SP_5504_ELEMENTALRESET | elementalReset |
| 5505 | SP_5505_ELEMENTALREGISTANCE | elementalRegistance |
| 5506 | SP_5506_DRAGONAURA | dragonAura |
| 5507 | SP_5507_DRAGONSKIN | dragonSkin |
| 5508 | SP_5508_EFFECT_BASICEFFIMG_DRAGONEYES | Effect/BasicEff.img/dragonEyes |
| 5509 | SP_5509_BAMBOOUSED | Bamboo_Used |
| 5510 | SP_5510__MESOS |  mesos |
| 5511 | SP_5511_WBASIC | #wbasic# |
| 5512 | SP_5512_WPROB | #wprob# |
| 5513 | SP_5513_WSELECT | #wselect# |
| 5514 | SP_5514_04D | %04d |
| 5515 | SP_5515_D | %d |
| 5516 | SP_5516_D___D | %d / %d |
| 5517 | SP_5517_S | %s |
| 5518 | SP_5518_S_S | %s (%s) |
| 5519 | SP_5519_S_OF_TEAM_MAPLE_KNOCKS_DOWN_A_COCONUT | %s of Team Maple knocks down a coconut. |
| 5520 | SP_5520_S_D | %s/%d |
| 5521 | SP_5521__SNOWBALL | /snowball |
| 5522 | SP_5522__SNOWBALL1 | /snowball1 |
| 5523 | SP_5523__SNOWBALL2 | /snowball2 |
| 5524 | SP_5524__SNOWBALL3 | /snowball3 |
| 5525 | SP_5525_ADVERTISING | Advertising |
| 5526 | SP_5526_ARAN | Aran |
| 5527 | SP_5527_ARIAL | Arial |
| 5528 | SP_5528_ATTACK2 | Attack2 |
| 5529 | SP_5529_BLAZE_WIZARD | Blaze Wizard |
| 5530 | SP_5530_CATEGORY_ | CATEGORY : |
| 5531 | SP_5531_CANNOT_BE_TRADED_DUE_TO_GENDER_INCONSISTENCIES | Cannot be traded due to gender inconsistencies. |
| 5532 | SP_5532_CASH | Cash |
| 5533 | SP_5533_CATEGORY | Category |
| 5534 | SP_5534_CHANGE_OCCURRED_IN_SYSTEM_TIME_BY_SPEED_HACK_RELATED_PROGRAM | Change occurred in system time by speed hack-related program. |
| 5535 | SP_5535_DAWN_WARRIOR | Dawn Warrior |
| 5536 | SP_5536_DUE_TO_JOB_LIMITATIONS_S_CANT_R_NUSE_THIS_ITEM_R_NDO_YOU_STILL_WISH_TO_GIVE_IT_A | Due to job limitations, %s can't\r\nuse this item.\r\nDo you still wish to give it as a gift? |
| 5537 | SP_5537_EXP__D___D | EXP : %d / %d |
| 5538 | SP_5538_ETC | Etc |
| 5539 | SP_5539_FAILED_TO_PURCHASE_THE_ITEM | Failed to purchase the item. |
| 5540 | SP_5540_FAMILY | Family |
| 5541 | SP_5541_GM | GM |
| 5542 | SP_5542_GUILD | Guild |
| 5543 | SP_5543_HIT | Hit |
| 5544 | SP_5544_IN_ADDITION_A_BIT_OF_DEX_IS_REQUIRED | In addition, A bit of DEX is required. |
| 5545 | SP_5545_ITEM | Item |
| 5546 | SP_5546_ITEM_PET_07DIMG | Item/Pet/%07d.img |
| 5547 | SP_5547_LEVELUP | LevelUp |
| 5548 | SP_5548_MAGICIAN | Magician |
| 5549 | SP_5549_MAPLEPOINT | MaplePoint |
| 5550 | SP_5550_MESO | Meso |
| 5551 | SP_5551_MOB | Mob |
| 5552 | SP_5552_NIGHT_WALKER | Night Walker |
| 5553 | SP_5553_NPC | Npc |
| 5554 | SP_5554_ONLINE_PARTY_MEMBERS___TOTAL_REGISTERED_PARTY_MEMBERS | Online Party Members / Total Registered Party Members |
| 5555 | SP_5555_ONLY_D_WILL_BE_SOLD | Only %d will be sold. |
| 5556 | SP_5556_PRAIDPOINT | PRaid_Point |
| 5557 | SP_5557_PET | Pet |
| 5558 | SP_5558_PLANT | Plant |
| 5559 | SP_5559_PLEASE_ENTER_THE_CONTENT | Please enter the content. |
| 5560 | SP_5560_PLEASE_ENTER_THE_PRICE_R_NOF_OVER_110_NX | Please enter the price\r\nof over 110 NX. |
| 5561 | SP_5561_PLEASE_ENTER_THE_TITLE | Please enter the title. |
| 5562 | SP_5562_PORTAL | Portal |
| 5563 | SP_5563_SUMMON | Summon |
| 5564 | SP_5564_TEXT02D | Text%02d |
| 5565 | SP_5565_THANK_YOU_FOR_YOUR_COOPERATION | Thank you for your cooperation. |
| 5566 | SP_5566_THE_GAME_FILE_HAS_BEEN_DAMAGED_R_NSO_YOU_CANNOT_PARTICIPATE_IN_AN_ITEM_TRADE_PLE | The game file has been damaged\r\nso you cannot participate in an item trade. Please try again\r\nafter reinstalling the game. |
| 5567 | SP_5567_THE_PASSWORD_IS_INCORRECT | The password is incorrect. |
| 5568 | SP_5568_THE_PASSWORD_NEEDS_TO_BE_AT_LEAST_D_LETTERS_LONG | The password needs to be at least %d letters long. |
| 5569 | SP_5569_THE_STORE_CANNOT_BE_OPENED_AS_THE_TIME_LIMIT_HAS_PASSED | The store cannot be opened as the time limit has passed. |
| 5570 | SP_5570_THE_TIME_LIMIT_HAS_EXPIRED | The time limit has expired. |
| 5571 | SP_5571_THIS_ITEM_CANT_BE_TAKEN_BACK_ONCE__R_NTHROWN_AWAY_WILL_YOU_STILL_DROP_IT | This item can't be taken back once \r\nthrown away. Will you still drop it? |
| 5572 | SP_5572_THIS_ITEM_CANNOT_BE_USED | This item cannot be used. |
| 5573 | SP_5573_THIS_ITEM_CANNOT_BE_R_NPURCHASED_BY_ANYONE_UNDER_7 | This item cannot be\r\npurchased by anyone under 7. |
| 5574 | SP_5574_THIS_ITEM_CANNOT_BE_R_NRECEIVED_BY_ANYONE_UNDER_7 | This item cannot be\r\nreceived by anyone under 7. |
| 5575 | SP_5575_THUNDER_BREAKER | Thunder Breaker |
| 5576 | SP_5576_TITLE | Title |
| 5577 | SP_5577_UI_BASICIMG_BTCLOSE | UI/Basic.img/BtClose |
| 5578 | SP_5578_UI_LOGINIMG_COMMON_SHADOW | UI/Login.img/Common/shadow |
| 5579 | SP_5579_UI_UIWINDOWIMG_EQUIP_PET | UI/UIWindow.img/Equip/pet |
| 5580 | SP_5580_UI_UIWINDOWIMG_ITEM_BTFULL | UI/UIWindow.img/Item/BtFull |
| 5581 | SP_5581_UI_UIWINDOWIMG_ITEM_BTSMALL | UI/UIWindow.img/Item/BtSmall |
| 5582 | SP_5582_UI_UIWINDOWIMG_KEYCONFIG_BTCANCEL | UI/UIWindow.img/KeyConfig/BtCancel |
| 5583 | SP_5583_UI_UIWINDOWIMG_KEYCONFIG_BTDEFAULT | UI/UIWindow.img/KeyConfig/BtDefault |
| 5584 | SP_5584_UI_UIWINDOWIMG_KEYCONFIG_BTOK | UI/UIWindow.img/KeyConfig/BtOK |
| 5585 | SP_5585_UI_UIWINDOWIMG_QUEST_S | UI/UIWindow.img/Quest/%s |
| 5586 | SP_5586_UI_UIWINDOWIMG_QUEST_BACKGRND4 | UI/UIWindow.img/Quest/backgrnd4 |
| 5587 | SP_5587_UI_UIWINDOWIMG_USERLIST_GUILD_BACKGRND | UI/UIWindow.img/UserList/Guild/backgrnd |
| 5588 | SP_5588_UI_UIWINDOWIMG_UTILDLGEXAVATAR_BACKGRND | UI/UIWindow.img/UtilDlgEx_Avatar/backgrnd |
| 5589 | SP_5589_UI_UIWINDOWIMG_UTILDLGEXPET_BACKGRND | UI/UIWindow.img/UtilDlgEx_Pet/backgrnd |
| 5590 | SP_5590_UI_UIWINDOWIMG_ITEMSEARCH_BTLEFT | UI/UIWindow.img/itemSearch/BtLeft |
| 5591 | SP_5591_UI_UIWINDOWIMG_ITEMSEARCH_BTRIGHT | UI/UIWindow.img/itemSearch/BtRight |
| 5592 | SP_5592_UNABLE_TO_APPROACH_DUE_TO_THE_FORCE_OF_THE_GROUND | Unable to approach due to the force of the ground. |
| 5593 | SP_5593_UNABLE_TO_RECEIVE_AUTHENTICATION_FILE_INFORMATION | Unable to receive authentication file information. |
| 5594 | SP_5594_UNABLE_TO_START_THREAD | Unable to start thread. |
| 5595 | SP_5595_UNKNOWN_ERROR_D | Unknown Error (%d). |
| 5596 | SP_5596_USE | Use |
| 5597 | SP_5597_WHISPER | Whisper |
| 5598 | SP_5598_WIND_ARCHER | Wind Archer |
| 5599 | SP_5599_YOU_DO_NOT_HAVE_ENOUGH_MESOS | You do not have enough mesos. |
| 5600 | SP_5600_YOU_HAVE_BEEN_EXPELLED_FROM_THE_GUILD | You have been expelled from the guild. |
| 5601 | SP_5601_YOU_HAVE_GIVEN_A_GIFT_TO | You have given a gift to |
| 5602 | SP_5602_YOU_HAVE_SUCCESSFULLY_PURCHASED_THE_ITEM | You have successfully purchased the item. |
| 5603 | SP_5603_YOUR_INVENTORY_IS_FULL | Your inventory is full. |
| 5604 | SP_5604__SPIRIT_WEEK_EVENT__ITEM_DROP_RATE_WILL_BE_DOUBLED_BETWEEN_3PM_AND_9PM_FOR_30_MI | [Spirit Week Event] Item Drop Rate will be doubled between 3pm and 9pm for 30 minutes starting at the top of the hour. |
| 5605 | SP_5605__SPIRIT_WEEK_EVENT__NPC_CASSANDRA_WILL_REWARD_YOU_WITH_THE_OWL_OF_MINERVA_IF_YOU | [Spirit Week Event] NPC Cassandra will reward you with the "Owl of Minerva" if you keep her Candle burning for 1 hour. (above Lv.10) |
| 5606 | SP_5606__SPIRIT_WEEK_EVENT__YOU_CAN_TRADE_IN_YOUR_SCROLL_WITH_A_SCROLL_THAT_HAS_A_HIGHER | [Spirit Week Event] You can trade in your scroll with a scroll that has a higher Success Rate of 5% from NPC Cassandra. (above Lv.10) |
| 5607 | SP_5607__SPIRIT_WEEK_EVENT__YOU_WILL_BE_GIVEN_A_HIGHER_CHANCE_OF_ACQUIRING_DESIRABLE_WEA | [Spirit Week Event] You will be given a higher chance of acquiring desirable weapons and armor, as well as a 5% discount on selected potions at the shop. |
| 5608 | SP_5608__SPIRIT_WEEK_EVENT__YOU_WILL_BE_REWARDED_THE_TELEPORT_ROCK_IF_YOU_KEEP_NPC_CASSA | [Spirit Week Event] You will be rewarded the "Teleport Rock" if you keep NPC Cassandra's Candle burning for 1 hour. (above Lv.10) |
| 5609 | SP_5609__SPIRIT_WEEK_EVENT__YOU_WILL_RECEIVE_2X_PARTY_BONUS_EXP | [Spirit Week Event] You will receive 2x Party Bonus EXP. |
| 5610 | SP_5610__SPIRIT_WEEK_EVENT__YOU_WILL_RECEIVE_AN_ADDITIONAL_30_BONUS_EXP_FOR_THE_FIRST_5_ | [Spirit Week Event] You will receive an additional 30% Bonus EXP for the first 5 quests you complete today. (Quests with less than 1 Bonus EXP excluded) |
| 5611 | SP_5611__R_NUSING_THE_COUPON | \r\nusing the coupon. |
| 5612 | SP_5612_A0 | a0 |
| 5613 | SP_5613_A1 | a1 |
| 5614 | SP_5614_ACTION | action |
| 5615 | SP_5615_ACTIVESKILLID | activeSkillID |
| 5616 | SP_5616_AM | am |
| 5617 | SP_5617_AND__R_N | and \r\n |
| 5618 | SP_5618_AUTOSPEAKING | autoSpeaking |
| 5619 | SP_5619_BACKGRND | backgrnd |
| 5620 | SP_5620_CONMP | conMP |
| 5621 | SP_5621_COUNT | count |
| 5622 | SP_5622_CRY | cry |
| 5623 | SP_5623_DEAD | dead |
| 5624 | SP_5624_DELAY | delay |
| 5625 | SP_5625_DEMANDSUMMARY | demandSummary |
| 5626 | SP_5626_DISABLE | disable |
| 5627 | SP_5627_FLOAT | float |
| 5628 | SP_5628_HEAD | head |
| 5629 | SP_5629_HEAL | heal |
| 5630 | SP_5630_HIDENAME | hideName |
| 5631 | SP_5631_HIT | hit |
| 5632 | SP_5632_HOT | hot |
| 5633 | SP_5633_HTTP__MAPLESTORYNEXONNET | http://maplestory.nexon.net |
| 5634 | SP_5634_INTERVAL | interval |
| 5635 | SP_5635_JUMP | jump |
| 5636 | SP_5636_LT | lt |
| 5637 | SP_5637_MAGIC | magic |
| 5638 | SP_5638_MASTERLEVEL | masterLevel |
| 5639 | SP_5639_MESO | meso |
| 5640 | SP_5640_MOVE | move |
| 5641 | SP_5641_NONE | none |
| 5642 | SP_5642_PARTY | party |
| 5643 | SP_5643_PET | pet |
| 5644 | SP_5644_PM | pm |
| 5645 | SP_5645_PRICE | price |
| 5646 | SP_5646_PROTECTTIME | protectTime |
| 5647 | SP_5647_RB | rb |
| 5648 | SP_5648_RECOVERY | recovery |
| 5649 | SP_5649_REGEN | regen |
| 5650 | SP_5650_REWARDSUMMARY | rewardSummary |
| 5651 | SP_5651_SAY | say |
| 5652 | SP_5652_SKILL | skill |
| 5653 | SP_5653_START | start |
| 5654 | SP_5654_SUCCESS | success |
| 5655 | SP_5655_SUMMARY | summary |
| 5656 | SP_5656_SUMMON | summon |
| 5657 | SP_5657_UNDEAD | undead |
