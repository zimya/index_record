Java.perform(function () {
    var Mapper = Java.use("com.zhihu.android.network.c");

    Mapper.apply.implementation = function (resp) {
        // resp 是 retrofit2.Response<ZHTemplateBean<T>>
        try {
            var body = resp.body();
            if (body !== null) {
                var data = body.data;
                if (data !== null) {
                    console.log("==== Zhihu API DATA ====");
                    console.log(JSON.stringify(data));
                }
            }
        } catch (e) {
            console.log("err:", e);
        }

        return this.apply(resp);
    };
});

  
