# 角色动作表（raw action code → 动作名）

> 162 条，code 连续 0..161。数据文件：`data/actions.tsv`；总体来源与免责说明见 [README](README.md)。

## 1. 它是什么

客户端渲染角色时，用 **raw action code** 到 `Character.wz/<皮肤>.img/<动作名>/<帧>` 取帧。
动作名不是硬编码在代码里，而是启动时从 [StringPool](stringpool.md) 解密后填入 `ACTIONDATA` 表。

## 2. 表结构

| 项 | 值 |
|---|---|
| 表 | `CAvatar::s_aCharacterActionData` @ `0x00BEC620` |
| 项大小 | `0x18` 字节（`ACTIONDATA`；首字段是动作名 BSTR，其余为帧/状态数据） |
| 项数 | 162（code 0..161） |
| 初始化 | `_dynamic_initializer_for__s_aCharacterActionData__` @ `0x004A38E7` |
| 查找 | `CAvatar::get_action_name_from_code(code)` @ `0x004A8CE6` → `s_aCharacterActionData[code].name` |
| 消费 | `CActionMan::load_item_action` @ `0x00412929`、`CActionMan::load_character_action` @ `0x0041272C` |

该表在文件内是 **BSS（全 0）**，启动时由初始化函数逐项执行
`StringPool::GetBSTR(<StringPool 索引>)` 填入，因此：

```text
raw action code  --[s_aCharacterActionData]-->  动作名  --[StringPool]-->  明文
```

## 3. 注意事项

1. **code 连续 0..161，无空洞**；`get_action_name_from_code` 直接下标、无越界检查。
2. **存在别名**（同一动作名被多个 code 引用），是客户端原始数据，不是导出错误：
   - `24/28 → swingO1`，`25 → swingO2`，`26/29 → swingO3`，`27 → shootF`，`30 → stabO1`
   - `45 → alert6`；`50/51/52 → magic2/magic3/magic5`（没有 magic4）
3. `heal(31)`、`jump(35)`、`dead(38)`、`recovery(94)`、`float(121)` 的名字来自 StringPool 的 5000+ 段，
   不在 1008–1146 的“动作名块”里，属正常。
4. **raw action ≠ 移动动作值（move action）**。移动动作经 `CAvatar::MoveAction2RawAction`
   （`0x00451EC8`）转换：入参 `a2` 的低 bit 作为朝向输出（`dir = a2 & 1`），
   其余位（`a2 >> 1`）查表映射到 raw code；`nAction` 在封包里的具体打包方式以协议实现为准。
   普通状态下的映射见下表；morph / superman 分支的映射不同，不要混用。
5. **大小写敏感**：`proneStab`、`assassinationS`、`swingT2PoleArm` 等拼 WZ 路径时不要改大小写。
6. **磁盘上不可见**：表是 BSS，只能从初始化函数提取；换构建后需重新扫描初始化函数，
   不能按固定地址硬读。
7. 动作码只对人物（`CAvatar`）有效；怪物/NPC/宠物/召唤物各有自己的动作表与码段。

### 附：普通状态 move action → raw action

来源：`CAvatar::MoveAction2RawAction` @ `0x00451EC8` 的 `else` 分支（非 morph、非 superman）。

| `a2 >> 1` | raw code | 动作名 |
|---:|---:|---|
| 0 | 0 | walk1（默认分支） |
| 1 | 0 / 1 | walk1 / walk2（按内部状态） |
| 2 | 2 / 3 | stand1 / stand2（按内部状态） |
| 3 | 35 | jump |
| 4 | 4 | alert |
| 5 | 33 | prone |
| 6 | 34 | fly |
| 7 | 36 | ladder |
| 8 | 37 | rope |
| 9 | 38 | dead |
| 10 | 39 | sit |

## 4. 完整表

| code | StringPool 索引 | 动作名 |
|---:|---:|---|
| 0 | 1008 | walk1 |
| 1 | 1009 | walk2 |
| 2 | 1037 | stand1 |
| 3 | 1038 | stand2 |
| 4 | 1010 | alert |
| 5 | 1012 | swingO1 |
| 6 | 1013 | swingO2 |
| 7 | 1014 | swingO3 |
| 8 | 1015 | swingOF |
| 9 | 1016 | swingT1 |
| 10 | 1017 | swingT2 |
| 11 | 1018 | swingT3 |
| 12 | 1019 | swingTF |
| 13 | 1020 | swingP1 |
| 14 | 1021 | swingP2 |
| 15 | 1022 | swingPF |
| 16 | 1023 | stabO1 |
| 17 | 1024 | stabO2 |
| 18 | 1025 | stabOF |
| 19 | 1026 | stabT1 |
| 20 | 1027 | stabT2 |
| 21 | 1028 | stabTF |
| 22 | 1031 | shoot1 |
| 23 | 1032 | shoot2 |
| 24 | 1012 | swingO1 |
| 25 | 1013 | swingO2 |
| 26 | 1014 | swingO3 |
| 27 | 1034 | shootF |
| 28 | 1012 | swingO1 |
| 29 | 1014 | swingO3 |
| 30 | 1023 | stabO1 |
| 31 | 5629 | heal |
| 32 | 1029 | proneStab |
| 33 | 1030 | prone |
| 34 | 1040 | fly |
| 35 | 5635 | jump |
| 36 | 1041 | ladder |
| 37 | 1043 | rope |
| 38 | 5623 | dead |
| 39 | 1045 | sit |
| 40 | 1046 | tired |
| 41 | 1011 | alert2 |
| 42 | 1081 | alert3 |
| 43 | 1082 | alert4 |
| 44 | 1083 | alert5 |
| 45 | 5069 | alert6 |
| 46 | 1042 | ladder2 |
| 47 | 1044 | rope2 |
| 48 | 1033 | shoot6 |
| 49 | 1048 | magic1 |
| 50 | 2732 | magic2 |
| 51 | 2733 | magic3 |
| 52 | 2734 | magic5 |
| 53 | 1086 | burster1 |
| 54 | 1087 | burster2 |
| 55 | 1047 | savage |
| 56 | 1084 | avenger |
| 57 | 1085 | assaulter |
| 58 | 1088 | prone2 |
| 59 | 1089 | assassination |
| 60 | 1090 | assassinationS |
| 61 | 1091 | rush |
| 62 | 1092 | rush2 |
| 63 | 3865 | brandish1 |
| 64 | 3866 | brandish2 |
| 65 | 3833 | sanctuary |
| 66 | 3859 | meteor |
| 67 | 3868 | paralyze |
| 68 | 3861 | blizzard |
| 69 | 3869 | genesis |
| 70 | 3864 | ninjastorm |
| 71 | 3867 | blast |
| 72 | 3862 | holyshield |
| 73 | 3863 | showdown |
| 74 | 3842 | resurrection |
| 75 | 3860 | chainlightning |
| 76 | 3844 | smokeshell |
| 77 | 1093 | handgun |
| 78 | 1094 | somersault |
| 79 | 1095 | straight |
| 80 | 1096 | eburster |
| 81 | 1097 | backspin |
| 82 | 1098 | eorb |
| 83 | 1099 | screw |
| 84 | 1100 | doubleupper |
| 85 | 1101 | dragonstrike |
| 86 | 1102 | doublefire |
| 87 | 1103 | triplefire |
| 88 | 1104 | fake |
| 89 | 1105 | airstrike |
| 90 | 1106 | edrain |
| 91 | 1107 | octopus |
| 92 | 1108 | backstep |
| 93 | 1109 | shot |
| 94 | 5648 | recovery |
| 95 | 1115 | fireburner |
| 96 | 1116 | coolingeffect |
| 97 | 1117 | fist |
| 98 | 1118 | timeleap |
| 99 | 1119 | rapidfire |
| 100 | 1120 | homing |
| 101 | 1121 | ghostwalk |
| 102 | 1122 | ghoststand |
| 103 | 1123 | ghostjump |
| 104 | 1124 | ghostproneStab |
| 105 | 1127 | ghostfly |
| 106 | 1125 | ghostladder |
| 107 | 1126 | ghostrope |
| 108 | 1128 | ghostsit |
| 109 | 1129 | cannon |
| 110 | 1130 | torpedo |
| 111 | 1131 | darksight |
| 112 | 1132 | bamboo |
| 113 | 5351 | pyramid |
| 114 | 1133 | wave |
| 115 | 1134 | blade |
| 116 | 5084 | souldriver |
| 117 | 1136 | firestrike |
| 118 | 5073 | flamegear |
| 119 | 5085 | stormbreak |
| 120 | 1139 | vampire |
| 121 | 5627 | float |
| 122 | 5088 | swingT2PoleArm |
| 123 | 5086 | swingP1PoleArm |
| 124 | 5087 | swingP2PoleArm |
| 125 | 5326 | doubleSwing |
| 126 | 5360 | tripleSwing |
| 127 | 5074 | fullSwingDouble |
| 128 | 5075 | fullSwingTriple |
| 129 | 5077 | overSwingDouble |
| 130 | 5078 | overSwingTriple |
| 131 | 5352 | rollingSpin |
| 132 | 5324 | comboSmash |
| 133 | 5323 | comboFenrir |
| 134 | 5325 | comboTempest |
| 135 | 5333 | finalCharge |
| 136 | 5071 | combatStep |
| 137 | 5332 | finalBlow |
| 138 | 5334 | finalToss |
| 139 | 5490 | magicmissile |
| 140 | 5491 | dragonSpark |
| 141 | 5493 | dragonBreathe |
| 142 | 5495 | breathe_prepare |
| 143 | 5492 | dragonIceBreathe |
| 144 | 5494 | icebreathe_prepare |
| 145 | 5496 | infinityExplosion |
| 146 | 5497 | superMagicmissile |
| 147 | 5498 | illusion |
| 148 | 5499 | magicFlare |
| 149 | 5504 | elementalReset |
| 150 | 5505 | elementalRegistance |
| 151 | 5506 | dragonAura |
| 152 | 5500 | magicBooster |
| 153 | 5501 | dragonShield |
| 154 | 5502 | dragonFury |
| 155 | 5503 | dragonFly |
| 156 | 5507 | dragonSkin |
| 157 | 1110 | shockwave |
| 158 | 1111 | demolition |
| 159 | 1114 | snatch |
| 160 | 5091 | windspear |
| 161 | 1113 | windshot |

## 5. 复现

```text
idat.exe -A -S"docs/client/tools/dump_action_table.py" BeiDou.i64
```

脚本扫描 `0x004A38E7` 函数体内的 ``push <StringPool 索引>`` + `StringPool::GetBSTR` +
``mov ecx, offset s_aCharacterActionData[i]`` 序列，得到 `(code, StringPool 索引)`，
再复用 StringPool 算法解密出名字。

定位锚点：`get_action_name_from_code` 签名 `55 8B EC 51 8B 45 ? 83 65 ? ? 8D 04 40`；
初始化函数签名 `B8 D4 21 A8 00`；`MoveAction2RawAction` 签名 `8B 44 24 ? 56 8B F1 8B 4C 24 ? 85 C9`。
