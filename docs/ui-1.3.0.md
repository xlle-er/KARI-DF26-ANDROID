# Android 原生界面约定

**语言 / Language:** [简体中文](#简体中文) · [English](#english)

## 简体中文

沿用 KariTheme 的 Material 3 颜色与 typography：primary、surface、onSurface、error 等语义色；不模拟 iOS 液态玻璃。默认深色，与原眼睛背景一致。

四页面采用 NavigationBar／Scaffold；触控目标至少 48dp。正文边距 16dp，间距 12dp，卡片内边距 16dp，沿用 MaterialTheme.shapes。输入使用 OutlinedTextField，开关 Switch，参数 Slider，预设 RadioButton。默认字体 Roboto，并跟随系统字体缩放。

页面切换不会创建新 Runtime、相机、录音器或 Live。中间播放有 TalkBack 标签但无第五个页面；运行时隐藏导航。停止位于右上角，与控制层同步显隐。文件导入和分享使用系统界面。遥控保留原生自绘手势及松手停止；眼睛保留原始本地组件。

## English

KariTheme uses Material 3 semantic colors and typography: primary, surface, onSurface and error. The default dark theme matches the eyes; it does not imitate iOS Liquid Glass.

Four pages use NavigationBar / Scaffold and touch targets of at least 48 dp. Content margins and card padding are 16 dp, spacing is 12 dp, and shapes use MaterialTheme.shapes. Inputs use OutlinedTextField, Switch, Slider and RadioButton. Roboto follows system font scaling.

Navigation never creates another Runtime, camera, recorder or Live session. The central play action has a TalkBack label and is not a fifth page. Navigation is hidden during companionship; the upper-right stop button follows the control layer's visibility. Files use native import/share UI. Remote controls retain native gestures and release-to-stop; the eyes remain a local component.
