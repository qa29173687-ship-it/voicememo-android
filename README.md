# 錄音記事本 (Android)

個人用錄音記事 App：錄音（背景/關螢幕可續錄）→ 自動離線轉成繁體中文文字 → 存成「提醒／備忘／筆記」，可上傳到 Google 雲端硬碟。

- 錄音：AAC m4a 44.1kHz 128kbps，暫停/繼續，通知列可停止
- 語音轉文字：sherpa-onnx + SenseVoice Small int8（約 240MB，第一次使用時下載）+ Silero VAD 切段，完全離線、免費；OpenCC 簡轉繁
- 分辨說話者：pyannote segmentation-3.0 + 3D-Speaker ERes2NetV2（約 77MB，下載一次），先分辨再逐段辨識，「說話者 N：」可改名，人數自動或 2–5+
- 整理待辦：一鍵把逐字稿送到 Grok App（用你自己的 Grok 帳號，不需 API 金鑰），再把回覆貼回來，勾選建立提醒／備忘
- 長錄音：串流解碼、背景前景服務、進度%、可取消、邊轉邊存
- 提醒：自動從逐字稿找最早的未來時間（如「明天下午三點」），到時通知
- 備忘：置頂、勾選完成；筆記：長文、標籤
- 錄音同步存到「音樂/錄音記事本」，檔名 `yyyyMMdd_HHmmss_標題前幾字.m4a`

Android 8.0 以上（arm64 / armv7）。畫面預覽在 `previews/`。
