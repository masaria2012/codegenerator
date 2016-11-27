package com.unit.code.classloader;

import java.io.File;
import java.io.FileNotFoundException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;

public class HotSwapURLClassLoader extends URLClassLoader {

    //�������class�ļ�����������޸�ʱ��
    public static Map<String,Long> cacheLastModifyTimeMap = new HashMap<String,Long>();

    //����class�����ڵ�·��
    public static String projectClassPath = "D:/Ecpworkspace/ZJob-Note/bin/";

    //���еĲ��Ե��඼��ͬһ������
    public static String packagePath = "testjvm/testclassloader/";

    private static HotSwapURLClassLoader hcl = new HotSwapURLClassLoader();

    public HotSwapURLClassLoader() {
        //����ClassLoader���ص�·��
        super(getMyURLs());
    }

    public static HotSwapURLClassLoader  getClassLoader(){
        return hcl;
    } 

    private static  URL[] getMyURLs(){
        URL url = null;
        try {
            url = new File(projectClassPath).toURI().toURL();
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
            return new URL[] { url };
    }

    /**
     * ��дloadClass��������˫��ί�л���("java."��ͷ���໹�ǻ���ϵͳĬ��ClassLoader����)
     */
    @Override
    public Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
        Class clazz = null;
        //�鿴HotSwapURLClassLoaderʵ�������£��Ƿ��Ѿ����ع�class
        //��ͬ��HotSwapURLClassLoaderʵ���ǲ��������
        clazz = findLoadedClass(name);
        if (clazz != null ) {
            if (resolve){
                resolveClass(clazz);
            }
            //���class�౻�޸Ĺ��������¼���
            if (isModify(name)) {
                hcl = new HotSwapURLClassLoader();
                clazz = customLoad(name, hcl);
            }
            return (clazz);
        }



        return customLoad(name,this);
     }


     /**
      * �Զ������
      * @param name
      * @param cl 
      * @return
      * @throws ClassNotFoundException
      */
     public Class customLoad(String name,ClassLoader cl) throws ClassNotFoundException {
         return customLoad(name, false,cl);
     }

     /**
      * �Զ������
      * @param name
      * @param resolve
     * @return
      * @throws ClassNotFoundException
      */
     public Class customLoad(String name, boolean resolve,ClassLoader cl) throws ClassNotFoundException {
         //findClass()���õ���URLClassLoader����������ClassLoader��findClass()����
         Class clazz = ((HotSwapURLClassLoader)cl).findClass(name);
         if (resolve)
             ((HotSwapURLClassLoader)cl).resolveClass(clazz);
         //�������class�ļ�������޸�ʱ��
         long lastModifyTime = getClassLastModifyTime(name);
         cacheLastModifyTimeMap.put(name,lastModifyTime);
         return clazz;
     }

     public Class<?> loadClass(String name) throws ClassNotFoundException {
         return loadClass(name,false);
     }

     @Override
     protected Class<?> findClass(String name) throws ClassNotFoundException {
         // TODO Auto-generated method stub
        return super.findClass(name);
     }

     /**
      * @param name
      * @return .class�ļ����µ��޸�ʱ��
      */
     private long getClassLastModifyTime(String name){
         String path = getClassCompletePath(name);
         File file = new File(path);
         if(!file.exists()){
            throw new RuntimeException(new FileNotFoundException(name));
         }
         return file.lastModified();
     }

     /**
      * �ж�����ļ����ϴα��Ƿ��޸Ĺ�
      * @param name
      * @return
      */
     private boolean isModify(String name){
        long lastmodify = getClassLastModifyTime(name);
        long previousModifyTime = cacheLastModifyTimeMap.get(name);
        if(lastmodify>previousModifyTime){
            return true;
       }
       return false;
     }

    /**
     * @param name
     * @return .class�ļ�������·�� (e.g. E:/A.class)
     */
     private String getClassCompletePath(String name){
         String simpleName = name.substring(name.lastIndexOf(".")+1);
         return projectClassPath+packagePath+simpleName+".class";
     }    
 }