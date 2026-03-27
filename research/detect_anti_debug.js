function getModuleFromAddr(addr) {
    var module = Process.findModuleByAddress(addr);
    return module ? module.name : "unknown";
}

Interceptor.attach(Module.findExportByName(null, "fopen"), {
    onEnter: function(args) {
        var path = args[0].readUtf8String();
        console.log("[fopen] Path: " + path + " | Caller Module: " + getModuleFromAddr(this.returnAddress));
        if (path && path.indexOf("maps") !== -1) {
            console.log("*** Possible anti-Frida/maps check! Backtrace:");
            console.log(Thread.backtrace(this.context, Backtracer.ACCURATE).map(DebugSymbol.fromAddress).join("\n"));
        }
    }
});

Interceptor.attach(Module.findExportByName(null, "open"), {
    onEnter: function(args) {
        var path = args[0].readUtf8String();
        console.log("[open] Path: " + path + " | Caller Module: " + getModuleFromAddr(this.returnAddress));
        if (path && path.indexOf("maps") !== -1) {
            console.log("*** Possible maps check! Backtrace:");
            console.log(Thread.backtrace(this.context, Backtracer.ACCURATE).map(DebugSymbol.fromAddress).join("\n"));
        }
    }
});

Interceptor.attach(Module.findExportByName(null, "ptrace"), {
    onEnter: function(args) {
        console.log("[ptrace] Called | Caller Module: " + getModuleFromAddr(this.returnAddress));
        console.log("*** Anti-debug/ptrace check! Backtrace:");
        console.log(Thread.backtrace(this.context, Backtracer.ACCURATE).map(DebugSymbol.fromAddress).join("\n"));
    }
});

Interceptor.attach(Module.findExportByName(null, "fread"), {
    onEnter: function(args) {
        console.log("[fread] Called | Caller Module: " + getModuleFromAddr(this.returnAddress));
    }
});

Interceptor.attach(Module.findExportByName(null, "strstr"), {
    onEnter: function(args) {
        var str1 = args[0].readUtf8String();
        var str2 = args[1].readUtf8String();
        if (str2 && str2.indexOf("frida") !== -1) {
            console.log("[strstr] Searching for 'frida' in: " + str1 + " | Caller Module: " + getModuleFromAddr(this.returnAddress));
            console.log("*** Frida string check! Backtrace:");
            console.log(Thread.backtrace(this.context, Backtracer.ACCURATE).map(DebugSymbol.fromAddress).join("\n"));
        }
    }
});

// Hook dlopen/android_dlopen_ext 来监控.so加载，并在加载后立即hook JNI_OnLoad（常见反调试入口）
var dlopen = Module.findExportByName(null, "dlopen");
var android_dlopen_ext = Module.findExportByName(null, "android_dlopen_ext");

if (dlopen) {
    Interceptor.attach(dlopen, {
        onEnter: function(args) {
            this.path = args[0].readUtf8String();
        },
        onLeave: function(retval) {
            if (retval != 0 && this.path) {
                console.log("[dlopen] Loaded: " + this.path);
            }
        }
    });
}

if (android_dlopen_ext) {
    Interceptor.attach(android_dlopen_ext, {
        onEnter: function(args) {
            this.path = args[0].readUtf8String();
        },
        onLeave: function(retval) {
            if (retval != 0 && this.path) {
                console.log("[android_dlopen_ext] Loaded: " + this.path);
                var module = Process.findModuleByName(this.path);
                if (module) {
                    var onload = Module.findExportByName(this.path, "JNI_OnLoad");
                    if (onload) {
                        console.log("*** Hooking JNI_OnLoad in " + this.path);
                        Interceptor.attach(onload, {
                            onEnter: function(args) {
                                console.log("[JNI_OnLoad] Called in " + this.path + " | Likely anti-debug here!");
                                console.log(Thread.backtrace(this.context, Backtracer.ACCURATE).map(DebugSymbol.fromAddress).join("\n"));
                            }
                        });
                    }
                }
            }
        }
    });
}

console.log("Anti-debug detection script loaded!");
