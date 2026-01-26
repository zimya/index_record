// var pth = Module.findExportByName(null,"open");
//     Interceptor.attach(ptr(pth),{
//         onEnter:function(args){
//             this.filename = args[0];
//             console.log("",this.filename.readCString())
//             if (this.filename.readCString().indexOf(".so") != -1){
//                 args[0] = ptr(0)
//             }
//         },onLeave:function(retval){
//             return retval;
//         }
//     })

function hook_dlopen() {
    Interceptor.attach(Module.findExportByName(null, "android_dlopen_ext"),
        {
            onEnter: function (args) {
                var pathptr = args[0];
                if (pathptr !== undefined && pathptr != null) {
                    var path = ptr(pathptr).readCString();
                    console.log("load " + path);
                }
            }
        }
    );
}
setImmediate(hook_dlopen)
