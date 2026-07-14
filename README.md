# [English](English.md) [中文](README.md)

<a href="https://jb.gg/OpenSourceSupport" target="_blank">
<img width="24" height="24" src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg?_gl=1*135yekd*_ga*OTY4Mjg4NDYzLjE2Mzk0NTE3MzQ.*_ga_9J976DJZ68*MTY2OTE2MzM5Ny4xMy4wLjE2NjkxNjMzOTcuNjAuMC4w&_ga=2.257292110.451256242.1669085120-968288463.1639451734" alt="idea"/>
</a>

<div align="center">
<img width="125" height="125" src="docs/archive_icon.svg" alt="阅读Archive"/>
<br>
阅读Archive
<br>
阅读Archive继承自 Lyc 维护的 Legado 分支，并延续 <a href="https://github.com/gedoor/legado" target="_blank">Legado</a> 的开源阅读体验，在其基础上继续增强界面、AI、EPUB、漫画、视频和主题能力。
</div>

## 阅读Archive特色
- 重做主题管理，支持日间/夜间主题、背景图、界面颜色、主题导入导出和云端同步。
- 深化 EPUB 原生阅读，持续补全图片、注解、分页缓存、复杂样式和大文件导入体验。
- 增强 AI 助手，支持工具调用、书源搜索、书籍与章节读取、阅读记录查询和联网搜索。
- 优化发现页与订阅源页，支持统一源选择、订阅内容搜索、纯 URL 订阅源和合并入口。
- 改进漫画和视频体验，强化漫画阅读控件、视频直达播放页和详情/目录信息展示。

## 2026-07-13 更新日志
- 新增 App 链接导入段落规则和气泡包，兼容 `legado://` 与 `yuedu://`。
- 在线包改用安全流式下载，限制大小和重定向，并对私有网络地址二次确认。
- 段落规则支持旧单对象、数组及带变量的版本化格式；Compose 导入窗口会汇总新增与同名冲突，并可选择自动编号保留、跳过或覆盖。
- 修复多行登录脚本、默认超时、变量名及重复规则名被在线导入误拒绝的问题。
- 气泡 ZIP 增加路径穿越、压缩炸弹、重复配置和外部 SVG 引用防护，并使用 staging/backup 原子安装与失败回滚。
- 本次未修改数据库结构，无需数据库迁移。

## 2026-07-14 更新日志
- 恢复 TXT 目录规则、字典规则、替换规则和书架分组在 Compose 管理页中的拖动排序；过滤状态下不会误写全局顺序。
- 气泡管理改为本地包与远端缓存优先展示、云端后台刷新，并阻止重复请求和旧容器结果覆盖。
- 气泡 SVG 预览移到后台线程生成并使用有限 LRU 缓存，减少进入页面、滚动和状态切换时的主线程卡顿与 Bitmap 抖动。
- Compose 弹窗统一为确认、表单、管理和宽屏四档响应式宽度，仅保留全宽选择器与边距预览等有明确用途的例外。
- 设置页、正文菜单和分组列表统一使用正文菜单风格的主题感知胶囊开关，自动适配深浅主题、强调色、禁用态和高对比度拇指颜色。
- 管理页、分组页与导入列表统一采用 8dp 卡片间距，避免列表项贴合，同时保留传统分割线列表的紧凑布局。
- 分组管理弹窗独立改用原位淡入淡出，并稳定异步列表首帧高度，避免弹窗看起来从上向下移动；通用 PopupMenu 保持原有行为。
- 重做书架标签管理页：支持分组快速切换、标签统计、主题化卡片和统一胶囊开关；新增标签时可搜索、多选全局已有标签或直接输入新标签；管理标签书籍时可搜索书名/作者、筛选已选或未选、已选优先展示，并批量选择或清空当前结果。
- 修复分组已有配置标签后，书籍中后来出现的实际标签不会进入管理列表的问题；配置标签与实际标签现在会按大小写不敏感规则合并。
- 正文菜单的字体粗细由正常、粗体、细体三档切换改为 100–900 连续字重滑杆；兼容旧主题配置，拖动结束后再刷新正文以避免频繁重排。
- 修复听书页手动切换上一章、下一章或从章节列表跳转时继承旧章节句内进度的问题；目标章节现在统一从开头重建朗读，并保留原播放/暂停状态。
- 修复听书原文模式没有持续同步服务 `cueIndex`、且使用错误 padding 坐标计算中心的问题；每次朗读句变化都会重新跟随并按实际行高精确居中，同时保留首尾句的居中空间。
- 本次未修改数据库结构，无需数据库迁移。

## 版本说明
- 测试版(beta)：包名与原版相同，可覆盖更新，版本更新频繁
- 正式版(plus)：新的共存包名，安装后是一个新软件，不会覆盖原版，每到一个稳定阶段进行一次更新
#### 找不到下载地址可以去这里 [下载软件](https://gitee.com/lyc486/legado/releases)

[![](https://img.shields.io/badge/-Contents:-696969.svg)](#contents) [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-) [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-) [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-) [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-) [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-) [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)

>新用户？
>
>软件不提供内容，需要您自己手动添加，例如导入书源等。
>看看 [官方帮助文档](https://www.yuque.com/legado/wiki)，也许里面就有你要的答案。

# Function-主要功能 [![](https://img.shields.io/badge/-Function-F5F5F5.svg)](#Function-主要功能-)
[English](English.md)

<details><summary>中文</summary>
1.自定义书源，自己设置规则，抓取网页数据，规则简单易懂，软件内有规则说明。<br>
2.列表书架，网格书架自由切换。<br>
3.书源规则支持搜索及发现，所有找书看书功能全部自定义，找书更方便。<br>
4.订阅内容,可以订阅想看的任何内容,看你想看<br>
5.支持替换净化，去除广告替换内容很方便。<br>
6.支持本地TXT、EPUB阅读，手动浏览，智能扫描。<br>
7.支持高度自定义阅读界面，切换字体、颜色、背景、行距、段距、加粗、简繁转换等。<br>
8.支持多种翻页模式，覆盖、仿真、滑动、滚动等。<br>
9.软件开源，持续优化，无广告。
</details>

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Community-交流社区 [![](https://img.shields.io/badge/-Community-F5F5F5.svg)](#Community-交流社区-)

#### Telegram
[![Telegram-channel](https://img.shields.io/badge/Σ_Telegram-%E9%A2%91%E9%81%93-blue)](https://t.me/readsigma)

#### WeChat
[![WeChat-channel](https://img.shields.io/badge/Σ_%e5%be%ae%e4%bf%a1-%e5%85%ac%e4%bc%97%e5%8f%b7-green)](https://mp.weixin.qq.com/s/f54f7yP9HQi6P5Wky8wE1A)  
<img src="https://open.weixin.qq.com/qr/code?username=legado_plus" width="100">

#### Discord
[![Discord](https://img.shields.io/discord/560731361414086666?color=%235865f2&label=Discord)](https://discord.gg/VtUfRyzRXn)

#### Other
https://www.yuque.com/legado/wiki/community

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# API [![](https://img.shields.io/badge/-API-F5F5F5.svg)](#API-)
* 阅读3.0 提供了2种方式的API：`Web方式`和`Content Provider方式`。您可以在[这里](api.md)根据需要自行调用。 
* 可通过url唤起阅读进行一键导入,url格式: legado://import/{path}?src={url}
* path类型: bookSource,rssSource,replaceRule,textTocRule,httpTTS,theme,readConfig,dictRule,[addToBookshelf](/app/src/main/java/io/legado/app/ui/association/AddToBookshelfDialog.kt)
* path类型解释: 书源,订阅源,替换规则,本地txt小说目录规则,在线朗读引擎,主题,阅读排版,添加到书架

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Other-其他 [![](https://img.shields.io/badge/-Other-F5F5F5.svg)](#Other-其他-)
##### 免责声明
https://gedoor.github.io/Disclaimer

##### 阅读3.0
* [书源规则](https://mgz0227.github.io/The-tutorial-of-Legado/)
* [更新日志](/app/src/main/assets/updateLog.md)
* [帮助文档](/app/src/main/assets/web/help/md/appHelp.md)
* [web端书架](https://github.com/gedoor/legado_web_bookshelf)
* [web端源编辑](https://github.com/gedoor/legado_web_source_editor)

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Grateful-感谢 [![](https://img.shields.io/badge/-Grateful-F5F5F5.svg)](#Grateful-感谢-)
> * org.jsoup:jsoup
> * cn.wanghaomiao:JsoupXpath
> * com.jayway.jsonpath:json-path
> * com.github.gedoor:rhino-android
> * com.squareup.okhttp3:okhttp
> * com.github.bumptech.glide:glide
> * org.nanohttpd:nanohttpd
> * org.nanohttpd:nanohttpd-websocket
> * cn.bingoogolapple:bga-qrcode-zxing
> * com.jaredrummler:colorpicker
> * org.apache.commons:commons-text
> * io.noties.markwon:core
> * io.noties.markwon:image-glide
> * com.hankcs:hanlp
> * com.positiondev.epublib:epublib-core
> * com.github.Moriafly:LyricViewX
> * io.github.rosemoe:editor
<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>

# Interface-界面 [![](https://img.shields.io/badge/-Interface-F5F5F5.svg)](#Interface-界面-)
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B1.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B2.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B3.jpg" width="270">
<img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B4.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B5.jpg" width="270"><img src="https://github.com/gedoor/gedoor.github.io/blob/master/static/img/legado/%E9%98%85%E8%AF%BB%E7%AE%80%E4%BB%8B6.jpg" width="270">

<a href="#readme">
    <img src="https://img.shields.io/badge/-返回顶部-orange.svg" alt="#" align="right">
</a>
