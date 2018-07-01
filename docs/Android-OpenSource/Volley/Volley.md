Volley地址[Volley](https://github.com/google/volley), Volley是Google开源的一个网络框架

# Demo
```
mQueue = Volley.newRequestQueue(this);
StringRequest stringRequest = new StringRequest("https://www.baidu.com/",
        new Response.Listener<String>() {
            @Override
            public void onResponse(String s) {
                Log.d("Volley", " " + s);
            }
        },
        new Response.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                Log.d("Volley", " " + volleyError);
            }
        });

mQueue.add(stringRequest);
```

![Volley Overview](https://upload-images.jianshu.io/upload_images/5688445-bea261e9980a28db.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

通过` Volley.newRequestQueue()`就可以开启Volley， 然后往Queue里加入一个Request, 就执行了一次Http请求了。相当的简单与方便。

Volley的框架由三部分组成，缓存分发层，网络分发层与结果递交层.

- 缓存分发层
缓存分发层单独运行在一个线程中，每次接收http请求时，会根据需求是否需要从Cache中查找，如果满足条件，那直接将结果传递给递交层，返回到主线程去执行，就不会再请求网络连接了。

- 网络分发层
网络分发层默认开启了4个线程，然后4个线程都从 mNetworkQueue这个BlockingQueue里去获得Request去分别执行，将执行的结果进入缓存，磁盘缓存等。最后递交给主线程去执行。

- 递交层
递交层挺简单，就是将结果post到主线程去执行。


Volley底层的实现在 SDK >= 9上使用的是HttpUrlConnection, 而在9以下使用的是HttpClient.

