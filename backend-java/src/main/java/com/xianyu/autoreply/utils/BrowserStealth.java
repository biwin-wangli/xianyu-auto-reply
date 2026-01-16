package com.xianyu.autoreply.utils;

public class BrowserStealth {

    public static final String STEALTH_SCRIPT = 
            "// 隐藏webdriver属性\n" +
            "Object.defineProperty(navigator, 'webdriver', {\n" +
            "    get: () => undefined,\n" +
            "});\n" +
            "\n" +
            "// 隐藏自动化相关属性\n" +
            "delete navigator.__proto__.webdriver;\n" +
            "delete window.navigator.webdriver;\n" +
            "delete window.navigator.__proto__.webdriver;\n" +
            "\n" +
            "// 模拟真实浏览器环境\n" +
            "window.chrome = {\n" +
            "    runtime: {},\n" +
            "    loadTimes: function() {},\n" +
            "    csi: function() {},\n" +
            "    app: {}\n" +
            "};\n" +
            "\n" +
            "// 覆盖plugins - 随机化\n" +
            "const pluginCount = Math.floor(Math.random() * 5) + 3;\n" +
            "Object.defineProperty(navigator, 'plugins', {\n" +
            "    get: () => Array.from({length: pluginCount}, (_, i) => ({\n" +
            "        name: 'Plugin' + i,\n" +
            "        description: 'Plugin ' + i\n" +
            "    })),\n" +
            "});\n" +
            "\n" +
            "// 覆盖languages\n" +
            "Object.defineProperty(navigator, 'languages', {\n" +
            "    get: () => ['zh-CN', 'zh', 'en'],\n" +
            "});\n" +
            "\n" +
            "// 隐藏自动化检测 - 随机化硬件信息\n" +
            "Object.defineProperty(navigator, 'hardwareConcurrency', { get: () => [2, 4, 6, 8][Math.floor(Math.random()*4)] });\n" +
            "Object.defineProperty(navigator, 'deviceMemory', { get: () => [4, 8, 16][Math.floor(Math.random()*3)] });\n" +
            "\n" +
            "// 伪装 Date\n" +
            "const OriginalDate = Date;\n" +
            "Date = function(...args) {\n" +
            "    if (args.length === 0) {\n" +
            "        const date = new OriginalDate();\n" +
            "        const offset = Math.floor(Math.random() * 3) - 1;\n" +
            "        return new OriginalDate(date.getTime() + offset);\n" +
            "    }\n" +
            "    return new OriginalDate(...args);\n" +
            "};\n" +
            "Date.prototype = OriginalDate.prototype;\n" +
            "Date.now = function() {\n" +
            "    return OriginalDate.now() + Math.floor(Math.random() * 3) - 1;\n" +
            "};\n" +
            "\n" +
            "// 伪装 RTCPeerConnection\n" +
            "if (window.RTCPeerConnection) {\n" +
            "    const originalRTC = window.RTCPeerConnection;\n" +
            "    window.RTCPeerConnection = function(...args) {\n" +
            "        const pc = new originalRTC(...args);\n" +
            "        const originalCreateOffer = pc.createOffer;\n" +
            "        pc.createOffer = function(...args) {\n" +
            "            return originalCreateOffer.apply(this, args).then(offer => {\n" +
            "                offer.sdp = offer.sdp.replace(/a=fingerprint:.*\\r\\n/g, \n" +
            "                    `a=fingerprint:sha-256 ${Array.from({length:64}, ()=>Math.floor(Math.random()*16).toString(16)).join('')}\\r\\n`);\n" +
            "                return offer;\n" +
            "            });\n" +
            "        };\n" +
            "        return pc;\n" +
            "    };\n" +
            "}\n" +
            "\n" +
            "// 伪装 Notification 权限\n" +
            "Object.defineProperty(Notification, 'permission', {\n" +
            "    get: function() {\n" +
            "        return ['default', 'granted', 'denied'][Math.floor(Math.random() * 3)];\n" +
            "    }\n" +
            "});\n" +
            "\n" +
            "// 隐藏 Playwright 特征\n" +
            "delete window.__playwright;\n" +
            "delete window.__pw_manual;\n" +
            "delete window.__PW_inspect;\n" +
            "\n" +
            "// 伪装 Permissions API\n" +
            "const originalQuery = window.navigator.permissions.query;\n" +
            "window.navigator.permissions.query = (parameters) => (\n" +
            "    parameters.name === 'notifications' ?\n" +
            "    Promise.resolve({ state: Notification.permission }) :\n" +
            "    originalQuery(parameters)\n" +
            ");\n";
}
