# ⚡ Marcy's Voxy Setup

An in-game, one-click tool that compiles and installs **NeoVoxy** directly inside your Minecraft client.
### IMPORTANT!
> **Requires Sodium 0.8.13+**  
> NeoVoxy relies on Sodium for rendering. Make sure you have Sodium 0.8.13 or newer installed before starting. only Sodium 0.8.13 has been confirmed working by testing.
---
## 🌟 Features
* **One-Click Setup:** No Git, terminal, or manual Gradle commands. Just click the button on the title screen.


* **Create Mod Support:** Pre-configured with distant train rendering, moving contraption LODs, tracks, and bogey shadows.


* **Automatic JAR Trimmer:** Drops unused native libraries for other operating systems, slimming the final mod from ~90MB down to ~13MB.


* **Clean & Isolated:** Downloads verified files over HTTPS, checks SHA-1 hashes, and compiles inside an isolated workspace folder (`neovoxy-builder-workspace/`).


* **Bundled JDK Support:** Automatically fetches a portable OpenJDK 21 from Adoptium if your launcher doesn't include a compiler.


* **Client-Side Only:** Runs completely locally. You can join any vanilla or modded server with it.
---
## 🛠️ How to Use
1. Put `marcys-voxy-setup-1.0.0.jar` into your Minecraft `mods` folder.
2. Launch Minecraft (NeoForge 1.21.1) to the **Title Screen**.
3. Click **"⚡ Setup NeoVoxy"** in the top-left corner.
4. Click **"Start Setup"** and give it ~30–60 seconds to compile.
5. Click **"Close Game"**, restart Minecraft, and enjoy your distant train LODs!
---

## ❓ FAQ
* **Can I delete this setup mod after installing NeoVoxy?**  
Yes. Once `neo-voxy-0.4.13-slim.jar` is generated in your `mods` folder, the setup mod automatically hides its button. You can leave it installed for future updates or safely delete the setup JAR.


* **Do I need Java or Gradle installed on my PC?**  
No. The mod handles Gradle automatically and provisions a portable OpenJDK 21 environment if needed.


* **Can I include this in a modpack?**  
Yes. Players just click setup on their first launch to generate their local copy of NeoVoxy.

---

<details>
<summary>Why is this an installer instead of a normal mod?</summary>
Upstream Voxy is licensed under **All Rights Reserved (ARR)**, meaning pre-compiled `.jar` binaries cannot legally be redistributed on platforms like Modrinth.
This tool solves the issue legally: it pulls the source code directly from GitHub and compiles your own personal copy locally on your machine.
</details>
