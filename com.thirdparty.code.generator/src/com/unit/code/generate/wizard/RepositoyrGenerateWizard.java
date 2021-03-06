package com.unit.code.generate.wizard;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;

import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.eclipse.core.resources.IResource;
import org.eclipse.jface.viewers.IStructuredSelection;

import com.unit.code.generate.constants.FormatConstant;
import com.unit.code.generate.constants.MysqlType2JavaTypeEnum;
import com.unit.code.generate.constants.SourceCodeConstant;
import com.unit.code.generate.domain.ColumnInfo;
import com.unit.code.generate.domain.MethodInfo;
import com.unit.code.generate.domain.MethodInfo.ParamInfo;
import com.unit.code.generate.domain.PropertyInfo;
import com.unit.code.generate.utils.AlertUtil;
import com.unit.code.generate.utils.CtClassUtils;
import com.unit.code.generate.utils.DbUtils;
import com.unit.code.generate.utils.JavaProjectUtils;
import com.unit.code.generate.utils.LogUtil;
import com.unit.code.generate.utils.StringUtils;
import com.unit.code.generate.velocity.VelocityUtils;

public class RepositoyrGenerateWizard extends AbstractBusinessGenerateWizard {

	private static final String CONFIG_MAPPER_XML_TEMPLATE_VM = "config/mapper_xml_template.vm";

	private static final String CONFIG_MAPPER_JAVA_TEMPLATE_VM = "config/mapper_java_template.vm";

	private static final String CONFIG_DAO_JAVA_TEMPLATE_VM = "config/dao_java_template.vm";

	private static final String CONFIG_DAO_IMPL_JAVA_TEMPLATE_VM = "config/dao_impl_java_template.vm";

	private static LogUtil logUtil = LogUtil.getInstance();

	private static ClassPool classPool = ClassPool.getDefault();

	private String javaBeanClassName;

	private String javaBeanName;

	private String currentDaoPackage;

	private String currentDaoImplPackage;

	private String currentMapperPackage;

	private String daoClassName;

	private String daoImplClassName;

	private String mapperClassName;

	private Template mapperXmlVelocityTemplate;

	private Template mapperJavaVelocityTemplate;

	private Template daoJavaVelocityTemplate;

	private Template daoImplJavaVelocityTemplate;

	private CtClass javaBeanClass;

	private List<ColumnInfo> insertColumnInfos = new ArrayList<ColumnInfo>();

	private List<ColumnInfo> updateColumnInfos = new ArrayList<ColumnInfo>();

	private List<ColumnInfo> primaryColumnInfos = new ArrayList<ColumnInfo>();

	private String primaryKeySeries;

	private String selectStatementId;

	private String insertStatementId;

	private String updateStatementId;

	private String deleteStatementId;

	private String mapperXmlFilePath;

	private String mapperJavaFilePath;

	private String daoJavaFilePath;

	private String daoImplJavaFilePath;

	private String mapperInstanceName;

	public RepositoyrGenerateWizard( IStructuredSelection selection ) {
		super( selection );
	}

	@Override
	public void addPages() {
		super.addPages();
		page.setTitle( "生成数据库存取相关代码" );
		page.setDescription( "该向导生成某个表的Mapper.xml、Mapper、Dao、DaoImpl代码" );
	}

	protected void initialize() {
		super.initialize();
		this.mapperXmlVelocityTemplate = this.velocityEngine.getTemplate( CONFIG_MAPPER_XML_TEMPLATE_VM );
		this.mapperJavaVelocityTemplate = this.velocityEngine.getTemplate( CONFIG_MAPPER_JAVA_TEMPLATE_VM );
		this.daoJavaVelocityTemplate = this.velocityEngine.getTemplate( CONFIG_DAO_JAVA_TEMPLATE_VM );
		this.daoImplJavaVelocityTemplate = this.velocityEngine.getTemplate( CONFIG_DAO_IMPL_JAVA_TEMPLATE_VM );
	}

	@Override
	public boolean generateFiles() throws Exception {
		prepareStatements();

		if ( this.primaryColumnInfos == null || this.primaryColumnInfos.isEmpty() ) {
			AlertUtil.alert( "提示", "无法生成文件，该表没有主键" );
			return false;
		}
		
		if( this.javaBeanClass == null ) {
			AlertUtil.alert( "提示", "无法生成文件，因为无法找到对应的java bean" );
			return false;
		}

		generateMapperXmlFile();

		generateMapperJavaFile();

		generateDaoJavaFile();

		generateDaoImplJavaFile();

		this.currentProject.getProject().getFolder( "src/main" ).refreshLocal( IResource.DEPTH_INFINITE, null );
		AlertUtil.alert( "提示", "文件已生成！" );
		return true;
	}

	@Override
	public void openFiles() throws Exception {
		JavaProjectUtils.openProjectFile( this.currentProject.getProject(), this.mapperXmlFilePath );
		JavaProjectUtils.openProjectFile( this.currentProject.getProject(), this.mapperJavaFilePath );
		JavaProjectUtils.openProjectFile( this.currentProject.getProject(), this.daoJavaFilePath );
		JavaProjectUtils.openProjectFile( this.currentProject.getProject(), this.daoImplJavaFilePath );
	}

	private void prepareStatements() {
		this.javaBeanClassName = DbUtils.getClassNameByTableName( this.tableName );
		this.javaBeanName = StringUtils.lowerFirstChar( this.javaBeanClassName );
		this.javaBeanClass = this.findJavaBeanClass();

		this.currentDaoPackage = this.currentPackageName.replaceFirst( "\\.dao.*$", "" ) + ".dao";
		this.currentDaoImplPackage = this.currentDaoPackage + ".impl";
		this.currentMapperPackage = this.currentDaoPackage + ".impl.mysql";

		this.primaryColumnInfos = this.getPrimaryColumnInfos();
		this.insertColumnInfos = this.getInsertColumnInfos();
		this.updateColumnInfos = this.getUpdateColumnInfos();
		this.primaryKeySeries = this.generatePrimaryKeySeries();
		this.selectStatementId = this.generateSelectStatementId();
		this.insertStatementId = this.generateInsertStatementId();
		this.updateStatementId = this.generateUpdateStatementId();
		this.deleteStatementId = this.generateDeleteStatementId();

		generateJavaClassNames();

		this.mapperInstanceName = StringUtils.lowerFirstChar( this.mapperClassName ).replaceFirst( "1$", "" );
	}

	private List<ColumnInfo> getPrimaryColumnInfos() {
		List<ColumnInfo> result = new ArrayList<ColumnInfo>();
		for( ColumnInfo columnInfo : this.columnInfos ) {
			if( columnInfo.isPrimary() ) {
				result.add( columnInfo );
			}
		}
		return result;
	}

	private CtClass findJavaBeanClass() {
		List<String> allJavaBeanFiles = new ArrayList<String>();

		String currentClassesPath = JavaProjectUtils.getBuildPath( this.currentProject );

		addJavaBeanFilesFromDirectory( new File( currentClassesPath ), allJavaBeanFiles );

		List<String> referencedPaths = JavaProjectUtils.getReferencedBuildPaths( this.currentProject );
		for( String referencedPath : referencedPaths ) {
			addJavaBeanFilesFromDirectory( new File( referencedPath ), allJavaBeanFiles );
		}

		List<CtClass> suspectedClasses = new ArrayList<CtClass>();
		for( String javaBeanFile : allJavaBeanFiles ) {
			CtClass ctClass = this.loadClass( new File( javaBeanFile ) );
			int privatePropertyCount = getPrivatePropertyCount( ctClass );
			if( privatePropertyCount == this.columnInfos.size() ) {
				suspectedClasses.add( ctClass );
			}
		}

		if( suspectedClasses.isEmpty() ) {
			return null;
		}

		return suspectedClasses.get( 0 );
	}

	private int getPrivatePropertyCount( CtClass ctClass ) {
		int privatePropertyCount = 0;
		for( CtField ctField : ctClass.getDeclaredFields() ) {
			if( CtClassUtils.isPrivate( ctField ) ) {
				privatePropertyCount++;
			}
		}

		return privatePropertyCount;
	}

	private void addJavaBeanFilesFromDirectory( File directory, List<String> javaBeanFiles ) {
		String javaBeanClassFileName = this.javaBeanClassName + ".class";
		if( directory.isDirectory() ) {
			File[] subFiles = directory.listFiles();
			for( File subFile : subFiles ) {
				if( subFile.isDirectory() ) {
					this.addJavaBeanFilesFromDirectory( subFile, javaBeanFiles );
				} else {
					if( subFile.getName().equals( javaBeanClassFileName ) ) {
						javaBeanFiles.add( subFile.getAbsolutePath() );
					}
				}
			}
		}
	}

	private void generateJavaClassNames() {
		String mapperXmlFilePath = this.getSrcResPath( this.currentMapperPackage ) + File.separator + this.javaBeanClassName + "Mapper.xml";
		String mapperJavaFilePath = this.getSrcJavaPath( this.currentMapperPackage ) + File.separator + this.javaBeanClassName + "Mapper.java";
		String daoJavaFilePath = this.getSrcJavaPath( this.currentMapperPackage ) + File.separator + this.javaBeanClassName + "Dao.java";
		String daoImplJavaFilePath = this.getSrcJavaPath( this.currentMapperPackage ) + File.separator + this.javaBeanClassName + "DaoImpl.java";

		if( new File( mapperXmlFilePath ).exists()
				|| new File( mapperJavaFilePath ).exists()
				|| new File( daoJavaFilePath ).exists()
				|| new File( daoImplJavaFilePath ).exists() ) {
			this.daoClassName = this.javaBeanClassName + "Dao1";
			this.daoImplClassName = this.javaBeanClassName + "DaoImpl1";
			this.mapperClassName = this.javaBeanClassName + "Mapper1";
		} else {
			this.daoClassName = this.javaBeanClassName + "Dao";
			this.daoImplClassName = this.javaBeanClassName + "DaoImpl";
			this.mapperClassName = this.javaBeanClassName + "Mapper";
		}
	}

	private void generateMapperXmlFile() {
		VelocityContext mapperXmlContext = generateMapperXmlVelocityContext();
		this.mapperXmlFilePath = this.getSrcResPath( this.currentMapperPackage ) + File.separator + this.mapperClassName + ".xml";
		VelocityUtils.merge( mapperXmlVelocityTemplate, mapperXmlContext, mapperXmlFilePath );
	}

	private void generateMapperJavaFile() {
		VelocityContext mapperJavaContext = generateMapperJavaVelocityContext();
		this.mapperJavaFilePath = this.getSrcJavaPath( this.currentMapperPackage ) + File.separator + this.mapperClassName + ".java";
		VelocityUtils.merge( mapperJavaVelocityTemplate, mapperJavaContext, mapperJavaFilePath );
	}

	private void generateDaoJavaFile() {
		VelocityContext daoJavaContext = generateDaoJavaVelocityContext();
		this.daoJavaFilePath = this.getSrcJavaPath( this.currentDaoPackage ) + File.separator + this.daoClassName + ".java";
		VelocityUtils.merge( daoJavaVelocityTemplate, daoJavaContext, daoJavaFilePath );
	}

	private void generateDaoImplJavaFile() {
		VelocityContext daoImplJavaContext = generateDaoImplJavaVelocityContext();
		this.daoImplJavaFilePath = this.getSrcJavaPath( this.currentDaoImplPackage ) + File.separator + this.daoImplClassName + ".java";
		VelocityUtils.merge( daoImplJavaVelocityTemplate, daoImplJavaContext, daoImplJavaFilePath );
	}

	private VelocityContext generateDaoImplJavaVelocityContext() {
		VelocityContext result = new VelocityContext();
		result.put( SourceCodeConstant.PACKAGE_NAME, this.currentDaoImplPackage );
		result.put( SourceCodeConstant.IMPORT_CLASSES, this.generateDaoImplImportClasses() );
		result.put( SourceCodeConstant.CLASS_NAME, this.daoImplClassName );
		result.put( SourceCodeConstant.IMPLEMENTS_INTERFACES, this.daoClassName );
		result.put( SourceCodeConstant.DECLARE_FIELDS, this.generateDaoImplProperties() );
		result.put( SourceCodeConstant.DECLARE_METHODS, this.generateDaoImplMethods() );
		return result;
	}

	private List<String> generateDaoImplMethods() {
		List<String> methods = new ArrayList<String>();

		MethodInfo selectMethodInfo = generateSelectMethodInfo();
		selectMethodInfo.setContent( "return " + this.mapperInstanceName + "." + selectMethodInfo.getInvokeStatement() );
		methods.add( selectMethodInfo.toStringWithMargin( FormatConstant.TAB ) + "\n" );

		MethodInfo addMethodInfo = generateAddMethodInfo();
		addMethodInfo.setContent( "return " + this.mapperInstanceName + "." + addMethodInfo.getInvokeStatement() );
		methods.add( addMethodInfo.toStringWithMargin( FormatConstant.TAB ) + "\n" );

		MethodInfo updateMethodInfo = generateUpdateMethodInfo();
		updateMethodInfo.setContent( "return " + this.mapperInstanceName + "." + updateMethodInfo.getInvokeStatement() );
		methods.add( updateMethodInfo.toStringWithMargin( FormatConstant.TAB ) + "\n" );

		MethodInfo deleteMethodInfo = generateDeleteMethodInfo();
		deleteMethodInfo.setContent( "return " + this.mapperInstanceName + "." + deleteMethodInfo.getInvokeStatement() );
		methods.add( deleteMethodInfo.toStringWithMargin( FormatConstant.TAB ) );

		return methods;
	}

	private List<String> generateDaoImplProperties() {
		List<String> result = new ArrayList<String>();

		PropertyInfo propertyInfo = new PropertyInfo();
		propertyInfo.setModifiers( "private" );
		propertyInfo.setAnnotation( "@Autowired" );
		propertyInfo.setPropertyType( this.mapperClassName );
		propertyInfo.setPropertyName( this.mapperInstanceName );
		result.add( propertyInfo.formatWithMargin( FormatConstant.TAB ) );

		return result;
	}

	private Object generateDaoImplImportClasses() {
		List<String> result = new ArrayList<String>();

		String qualifiedDaoClassName = this.currentDaoPackage + "." + this.daoClassName;
		String qualifiedMapperClassName = this.currentMapperPackage + "." + this.mapperClassName;

		result.add( "import org.springframework.stereotype.Repository;" );
		result.add( "import org.springframework.beans.factory.annotation.Autowired;" );
		result.add( "import " + this.javaBeanClass.getName() + ";" );
		result.add( "import " + qualifiedDaoClassName + ";" );
		result.add( "import " + qualifiedMapperClassName + ";" );

		return result;
	}

	private List<String> generateDaoImportClasses() {
		List<String> result = new ArrayList<String>();
		result.add( "import " + this.javaBeanClass.getName() + ";" );
		return result;
	}

	private VelocityContext generateDaoJavaVelocityContext() {
		VelocityContext result = new VelocityContext();
		result.put( SourceCodeConstant.PACKAGE_NAME, this.currentDaoPackage );
		result.put( SourceCodeConstant.IMPORT_CLASSES, this.generateDaoImportClasses() );
		result.put( SourceCodeConstant.CLASS_NAME, this.daoClassName );
		result.put( SourceCodeConstant.DECLARE_METHODS, this.generateDaoMethods() );
		return result;
	}

	private List<String> generateDaoMethods() {
		List<String> methods = new ArrayList<String>();

		MethodInfo selectMethodInfo = generateSelectMethodInfo();
		methods.add( FormatConstant.TAB + selectMethodInfo.getDeclaration() + ";\n" );

		MethodInfo addMethodInfo = generateAddMethodInfo();
		methods.add( FormatConstant.TAB + addMethodInfo.getDeclaration() + ";\n" );

		MethodInfo updateMethodInfo = generateUpdateMethodInfo();
		methods.add( FormatConstant.TAB + updateMethodInfo.getDeclaration() + ";\n" );

		MethodInfo deleteMethodInfo = generateDeleteMethodInfo();
		methods.add( FormatConstant.TAB + deleteMethodInfo.getDeclaration() + ";" );

		return methods;
	}

	private MethodInfo generateDeleteMethodInfo() {
		MethodInfo deleteMethodInfo = new MethodInfo();
		deleteMethodInfo.setReturnType( "int" );
		deleteMethodInfo.setModifiers( "public" );
		deleteMethodInfo.setAnnotation( "@Override" );
		deleteMethodInfo.setName( this.deleteStatementId );
		deleteMethodInfo.setParamList( this.generateParamsFromColumnInfos( this.primaryColumnInfos ) );
		return deleteMethodInfo;
	}

	private MethodInfo generateUpdateMethodInfo() {
		MethodInfo updateMethodInfo = new MethodInfo();
		updateMethodInfo.setReturnType( "int" );
		updateMethodInfo.setModifiers( "public" );
		updateMethodInfo.setAnnotation( "@Override" );
		updateMethodInfo.setName( this.updateStatementId );
		updateMethodInfo.setParamList( Arrays.asList( new ParamInfo( this.javaBeanName, this.javaBeanClassName ) ) );
		return updateMethodInfo;
	}

	private MethodInfo generateAddMethodInfo() {
		MethodInfo addMethodInfo = new MethodInfo();
		addMethodInfo.setModifiers( "public" );
		addMethodInfo.setReturnType( "int" );
		addMethodInfo.setAnnotation( "@Override" );
		addMethodInfo.setName( this.insertStatementId );
		addMethodInfo.setParamList( Arrays.asList( new ParamInfo( this.javaBeanName, this.javaBeanClassName ) ) );
		return addMethodInfo;
	}

	private MethodInfo generateSelectMethodInfo() {
		MethodInfo selectMethodInfo = new MethodInfo();
		selectMethodInfo.setModifiers( "public" );
		selectMethodInfo.setAnnotation( "@Override" );
		selectMethodInfo.setName( this.selectStatementId );
		selectMethodInfo.setReturnType( this.javaBeanClassName );
		selectMethodInfo.setParamList( this.generateParamsFromColumnInfos( this.primaryColumnInfos ) );
		return selectMethodInfo;
	}

	private List<ParamInfo> generateParamsFromColumnInfos( List<ColumnInfo> columnInfos ) {
		List<ParamInfo> result = new ArrayList<ParamInfo>( columnInfos.size() );
		for( ColumnInfo columnInfo : columnInfos ) {
			ParamInfo param = new ParamInfo();
			param.setName( DbUtils.generatePropertyNameWithColumnName( columnInfo.getColumnName() ) );
			MysqlType2JavaTypeEnum mysqlType2JavaTypeEnum = MysqlType2JavaTypeEnum.getEnumByMysqlType( columnInfo.getColumnType() );
			if( mysqlType2JavaTypeEnum != null ) {
				param.setType( mysqlType2JavaTypeEnum.getJavaType() );
			}
			result.add( param );
		}

		return result;
	}

	private VelocityContext generateMapperXmlVelocityContext() {
		VelocityContext result = new VelocityContext();

		StringBuilder builder = new StringBuilder();
		builder.append( generateTopMapperElement() ).append( "\n\n" );
		builder.append( generateSelectElement() ).append( "\n\n" );
		builder.append( generateInsertElement() ).append( "\n\n" );
		builder.append( generateUpdateElement() ).append( "\n\n" );
		builder.append( generateDeleteElement() ).append( "\n\n" );
		builder.append( "</mapper>" );

		result.put( "xml_content", builder.toString() );
		return result;
	}

	private VelocityContext generateMapperJavaVelocityContext() {
		VelocityContext result = new VelocityContext();

		result.put( SourceCodeConstant.PACKAGE_NAME, this.currentMapperPackage );
		result.put( SourceCodeConstant.IMPORT_CLASSES, this.generateMapperImportClasses() );
		result.put( SourceCodeConstant.CLASS_NAME, this.mapperClassName );
		result.put( SourceCodeConstant.DECLARE_METHODS, this.generateMapperMethods() );

		return result;
	}

	private List<String> generateMapperMethods() {

		List<String> methods = new ArrayList<String>();

		MethodInfo selectMethodInfo = generateSelectMethodInfo();
		methods.add( FormatConstant.TAB + selectMethodInfo.getMapperDeclaration() + ";\n" );

		MethodInfo addMethodInfo = generateAddMethodInfo();
		methods.add( FormatConstant.TAB + addMethodInfo.getMapperDeclaration() + ";\n" );

		MethodInfo updateMethodInfo = generateUpdateMethodInfo();
		methods.add( FormatConstant.TAB + updateMethodInfo.getMapperDeclaration() + ";\n" );

		MethodInfo deleteMethodInfo = generateDeleteMethodInfo();
		methods.add( FormatConstant.TAB + deleteMethodInfo.getMapperDeclaration() + ";" );

		return methods;
	}

	private List<String> generateMapperImportClasses() {
		List<String> importedClasses = new ArrayList<String>();
		importedClasses.add( "import org.apache.ibatis.annotations.Param;" );
		importedClasses.add( "import org.springframework.stereotype.Repository;" );
		importedClasses.add( "import " + this.javaBeanClass.getName() + ";" );

		return importedClasses;
	}

	private String generateDeleteElement() {
		String margin = FormatConstant.TAB;
		StringBuilder builder = new StringBuilder();
		builder.append( margin ).append( "<delete id=\"" ).append( this.deleteStatementId ).append( "\">\n" );
		builder.append( this.generateDeleteStatement() );
		builder.append( margin ).append( "</delete>" );
		return builder.toString();
	}

	private String generateDeleteStatement() {
		String margin = FormatConstant.DOUBLE_TAB;
		StringBuilder builder = new StringBuilder();
		builder.append( margin ).append( "DELETE FROM " ).append( this.tableName ).append( " WHERE" ).append( this.generateUniqueCondition() ).append( "\n" );
		return builder.toString();
	}

	private String generateUpdateElement() {
		String margin = FormatConstant.TAB;
		StringBuilder builder = new StringBuilder();
		builder.append( margin ).append( "<update id=\"" ).append( this.updateStatementId ).append( "\">\n" );
		builder.append( this.generateUpdateStatement() );
		builder.append( margin ).append( "</update>" );
		return builder.toString();
	}

	private String generateUpdateStatement() {
		int columnCountInLine = 5;
		String margin = FormatConstant.THREE_TAB + FormatConstant.THREE_SPACE;

		StringBuilder builder = new StringBuilder();
		builder.append( FormatConstant.DOUBLE_TAB ).append( "UPDATE " ).append( this.tableName ).append( " SET " );
		for( int i = 0; i < this.updateColumnInfos.size(); i++ ) {
			ColumnInfo columnInfo = updateColumnInfos.get( i );
			if( i != 0 && i % columnCountInLine == 0 ) {
				builder.append( "\n" ).append( margin );
			}
			builder.append( columnInfo.getColumnName() ).append( "=" ).append( this.generatePropertyVarWithBean( columnInfo.getColumnName() ) );

			if( i < ( this.updateColumnInfos.size() - 1 ) ) {
				builder.append( ", " );
			}
		}
		builder.append( "\n" );
		builder.append( FormatConstant.DOUBLE_TAB ).append( "WHERE" ).append( this.generateUniqueConditionWithBean() ).append( "\n" );

		return builder.toString();
	}

	private String generatePropertyVarWithBean( String columnName ) {
		return "#{" + this.javaBeanName + "." + DbUtils.generatePropertyNameWithColumnName( columnName ) + "}";
	}

	private String generatePropertyVar( String columnName ) {
		return "#{" + DbUtils.generatePropertyNameWithColumnName( columnName ) + "}";
	}

	private String generateInsertElement() {
		String margin = FormatConstant.TAB;
		StringBuilder builder = new StringBuilder();
		if ( this.primaryColumnInfos.size() == 1 && this.primaryColumnInfos.get( 0 ).getColumnName().equals( "FID" )) {
			builder.append( margin ).append( "<insert id=\"" ).append( this.insertStatementId ).append( "\" useGeneratedKeys=\"true\" keyProperty=\"" )
			.append( this.javaBeanName ).append( ".id\">" ).append( "\n" );
		} else {
			builder.append( margin ).append( "<insert id=\"" ).append( this.insertStatementId ).append( "\" >" ).append( "\n" );
		}
		builder.append( this.generateInsertStatement() );
		builder.append( margin ).append( "</insert>" );
		return builder.toString();
	}

	private String generateInsertStatement() {
		int columnCountInLine = 5;
		String margin = FormatConstant.THREE_TAB + FormatConstant.THREE_SPACE;
		StringBuilder builder = new StringBuilder();
		builder.append( FormatConstant.DOUBLE_TAB ).append( "INSERT INTO " ).append( this.tableName ).append( "(" );
		for( int i = 0; i < this.insertColumnInfos.size(); i++ ) {
			ColumnInfo columnInfo = insertColumnInfos.get( i );
			if( i % columnCountInLine == 0 ) {
				builder.append( "\n" ).append( margin );
			}
			builder.append( columnInfo.getColumnName() );
			if( i < ( this.insertColumnInfos.size() - 1 ) ) {
				builder.append( ", " );
			}
		}
		builder.append( ")\n" );

		builder.append( FormatConstant.DOUBLE_TAB ).append( "VALUES(" );
		for( int i = 0; i < this.insertColumnInfos.size(); i++ ) {
			ColumnInfo columnInfo = insertColumnInfos.get( i );
			if( i > 0 && i % columnCountInLine == 0 ) {
				builder.append( "\n" ).append( margin );
			}
			builder.append( this.generatePropertyVarWithBean( columnInfo.getColumnName() ) );
			if( i < ( this.insertColumnInfos.size() - 1 ) ) {
				builder.append( ", " );
			}
		}
		builder.append( ")\n" );
		return builder.toString();
	}

	private String generateTopMapperElement() {
		StringBuilder builder = new StringBuilder();
		builder.append( "<mapper namespace=\"" ).append( this.currentMapperPackage + "." + this.mapperClassName ).append( "\">" );
		return builder.toString();
	}

	private String generateSelectElement() {
		String margin = FormatConstant.TAB;
		StringBuilder builder = new StringBuilder();
		builder.append( margin ).append( "<select id=\"" ).append( this.selectStatementId ).append( "\" resultType=\"" )
				.append( this.javaBeanClass.getName() ).append( "\">" ).append( "\n" );
		builder.append( generateSelectStatement() );
		builder.append( margin ).append( "</select>" );
		return builder.toString();
	}

	private String generateSelectStatement() {
		String margin = FormatConstant.THREE_TAB + FormatConstant.THREE_SPACE;
		StringBuilder builder = new StringBuilder();
		String tableAlias = DbUtils.generateAliasForTable( tableName );
		for( int i = 0; i < this.columnInfos.size(); i++ ) {
			ColumnInfo columnInfo = this.columnInfos.get( i );
			String columnPropertyPair = tableAlias + "." + columnInfo.getColumnName() + FormatConstant.SPACE
					+ DbUtils.generatePropertyNameWithColumnName( columnInfo.getColumnName() );
			if( i == 0 ) {
				builder.append( FormatConstant.DOUBLE_TAB ).append( "SELECT" ).append( FormatConstant.SPACE ).append( columnPropertyPair );
			} else {
				builder.append( margin ).append( columnPropertyPair );
			}

			if( i < ( columnInfos.size() - 1 ) ) {
				builder.append( FormatConstant.COMMA );
			}
			builder.append( "\n" );
		}
		builder.append( FormatConstant.DOUBLE_TAB ).append( "FROM " ).append( this.tableName ).append( FormatConstant.SPACE ).append( tableAlias ).append( "\n" );
		builder.append( FormatConstant.DOUBLE_TAB ).append( "WHERE" ).append( this.generateUniqueConditionWithTableAlias( tableAlias ) ).append( "\n" );

		return builder.toString();
	}

	private String generatePrimaryKeySeries() {
		StringBuilder builder = new StringBuilder();
		for( int i = 0; i < this.primaryColumnInfos.size(); i++ ) {
			ColumnInfo columnInfo = this.primaryColumnInfos.get( i );
			if( i > 0 && i == this.primaryColumnInfos.size() - 1 ) {
				builder.append( "And" ).append( StringUtils.upperFirstChar( DbUtils.generatePropertyNameWithColumnName( columnInfo.getColumnName() ) ) );
			} else {
				builder.append( StringUtils.upperFirstChar( DbUtils.generatePropertyNameWithColumnName( columnInfo.getColumnName() ) ) );
			}
		}
		return builder.toString();
	}

	private String generateUniqueCondition() {
		return this.generateUniqueConditionWithTableAlias( null );
	}
	
	private String generateUniqueConditionWithTableAlias( String tableAlias ) {
		StringBuilder builder = new StringBuilder();
		String columnPrefix = "";
		if ( !StringUtils.isEmpty( tableAlias )) {
			columnPrefix = tableAlias + ".";
		}
		for( ColumnInfo columnInfo : this.primaryColumnInfos ) {
			builder.append( " AND " ).append( columnPrefix ).append( columnInfo.getColumnName() ).append( "=" ).append( this.generatePropertyVar( columnInfo.getColumnName() ) );
		}
		return builder.toString().replaceFirst( " AND", "" );
	}

	private String generateUniqueConditionWithBean() {
		StringBuilder builder = new StringBuilder();
		for( ColumnInfo columnInfo : this.primaryColumnInfos ) {
			builder.append( " AND " ).append( columnInfo.getColumnName() ).append( "=" ).append( this.generatePropertyVarWithBean( columnInfo.getColumnName() ) );
		}
		return builder.toString().replaceFirst( " AND", "" );
	}

	private CtClass loadClass( File file ) {
		InputStream stream = null;
		try {
			stream = new FileInputStream( file );
			return classPool.makeClass( stream );
		} catch( FileNotFoundException e ) {
			logUtil.logError( "未找到文件" + file.getAbsolutePath(), e );
		} catch( IOException e ) {
			logUtil.logError( "加载文件IO错误：" + file.getAbsolutePath(), e );
		} catch( Exception e ) {
			logUtil.logError( "加载文件运行时异常错误：" + file.getAbsolutePath(), e );
		} finally {
			if( stream != null ) {
				try {
					stream.close();
				} catch( IOException e ) {
					logUtil.logError( "关闭文件流错误", e );
				}
			}
		}

		return null;
	}

	private List<ColumnInfo> getInsertColumnInfos() {
		List<ColumnInfo> result = new ArrayList<ColumnInfo>();
		for( ColumnInfo columnInfo : this.columnInfos ) {
			if( !columnInfo.isAutoIncrement() ) {
				result.add( columnInfo );
			}
		}
		return result;
	}

	private List<ColumnInfo> getUpdateColumnInfos() {
		List<ColumnInfo> result = new ArrayList<ColumnInfo>();
		for( ColumnInfo columnInfo : this.columnInfos ) {
			if( !columnInfo.isPrimary() && !columnInfo.isAutoIncrement() ) {
				result.add( columnInfo );
			}
		}
		return result;
	}

	private String generateSelectStatementId() {
		return "getBy" + this.primaryKeySeries;
	}

	private String generateInsertStatementId() {
		return "add" + this.javaBeanClassName;
	}

	private String generateUpdateStatementId() {
		return "updateBy" + this.primaryKeySeries;
	}

	private String generateDeleteStatementId() {
		return "deleteBy" + this.primaryKeySeries;
	}
}
