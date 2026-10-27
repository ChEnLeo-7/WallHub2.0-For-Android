# WallHub MD3 全界面 Web 镜像

这是依据当前 Jetpack Compose 源码制作的独立 Web 交互镜像，用于在修改 Android 生产界面之前评审视觉与交互。

## 当前局域网地址

```text
http://192.168.2.155:4180/?v=4
```

服务命令：

```bash
cd prototypes/mpkg-tier-preview
python3 -m http.server 4180 --bind 0.0.0.0
```

## 源码对齐原则

- 使用 WallHub 当前静态明暗色板及 MD3 semantic color roles。
- 使用 WallHub 的间距、形状、触控尺寸、最大内容宽度和 adaptive navigation tokens。
- Compact 使用底部 NavigationBar；Medium 使用 NavigationRail；Expanded 使用 240dp PermanentDrawer。
- 二级页面隐藏顶级导航，使用返回 app bar，与 Compose 导航壳一致。
- 只展示当前源码真正可达的控件；例如 Local 不展示源码中未接入的筛选和列表/网格开关。
- Steam、文件选择、权限、安装器等系统能力使用假数据和交互状态模拟，不发送真实凭据、不读取真实文件。

## 已覆盖界面

### 应用壳与首次设置

- 启动/设置向导：欢迎、网络、连接检测、保存位置、Steam、完成。
- Compact / Medium / Expanded 三类导航布局。

### 首页与发现

- 首页 grid/list、统计标题、分页、悬浮搜索、筛选抽屉。
- 发现内容 rails、你的内容、关注查询、查询编辑、结果页。
- Workshop 详情、详情/评论标签、下载操作、视频占位播放器。

### 我的与个人内容

- Profile 头像、运行时间、订阅/收藏计数、下载和本地入口。
- 资料库订阅/收藏标签、搜索、筛选、壁纸网格。
- 下载中/已完成标签、进度、任务操作、重新转换档位底部弹层。
- 本地列表和本地 MPKG 详情。
- MPKG 转换档位：文件声明、内容推断、模糊、未知四种状态。

### 设置

- 设置索引。
- 常规、外观、下载、Steam、关于五个内部子页面。
- DoH 管理、开关、代理、缓存、转换质量、更新和重新运行设置向导等状态。

## 文件

- `index.html`：应用入口。
- `styles.css`：WallHub/MD3 tokens、组件和 adaptive layout。
- `app.js`：假数据、路由、页面和交互状态。

该镜像不会修改 Android 生产源码或设备应用数据。
