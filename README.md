fileupload
==========

文件上传组件

使用方式：
        public class Helloweb extends HttpServlet {
          protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException,   IOException {
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

          protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            this.doPost(request, response);
          }
        }
