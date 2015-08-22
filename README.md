fileupload
==========

这是一个HTTP文件上传组件

###功能

将form表单post过来的内容或者文件解析为可以单独使用对象

用户将得到一个列表，列表中存放解析后的对象。


###用法示例：

```java
public class Helloweb extends HttpServlet {
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
        throws ServletException,   IOException {
        request.setCharacterEncoding("utf-8");
        List<Part> parts =  new Upload().parseRequest(request);
        for (Part part : parts) {
            System.out.print(part.getField());
            System.out.print("--");
            if(part.isFormField()) {
                System.out.println(part.getValue());
            }else{
                System.out.println(part.getFileName());
                part.write(new File("/home/sllx/tmp/" + part.getFileName()));
            }
        }
    }
}
```
