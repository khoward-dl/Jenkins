#!groovy
/*
    Parameters:
    Name        Description
    projectName Name of the Jenkins project to supply the deployment artifacts
    webServer   The web server
    proxyServer The proxy server
    dbServer    The database server
    dbBackupDir The location of the SQL Server backup folder on the dbServer
    installType SharedInstance or Sandbox
    odsType     The type of ODS to deploy (ConfigSpecificSandbox, SharedInstance, or YearSpecific)
    odsYears    School Years represented e.g. 2017;2018
    adminInit   If the admin web site will be initialized
    adminPwd    Password for the sandbox admin user
    trans       Are these databases going to be reset on each deploy? (transient is a keyword in Java/Groovy) true or false
    isProd      Is this a production deployment?
    fromAddress From email address for key retrieval emails
    host        SMTP host for sending email
    userName    SMTP userName for sending email
    password    SMTP password for sending email
    port        SMTP port for sending email
    enableSsl   Enable SSL for SMTP email
*/
if (params.isProd == "true" && params.installType == "Sandbox") {
    error("Production deployment cannot be of type Sandbox.");
}
if (!params.odsYears?.trim()) {
    error("odsYears is a required parameter.");
}
parallel (
    'DeployToDatabase': {
        node('master') {
            stage('PullArtifactsDatabase') {
                copyArtifacts(projectName: "${projectName}", flatten: true, target: "Archive")
            }
            stage('MoveDatabaseArchive') {
                dir('Archive') {
                    bat 'if not exist EdFi.RestApi.Databases mkdir EdFi.RestApi.Databases'
                    bat 'move EdFi.RestApi.Databases.zip EdFi.RestApi.Databases/EdFi.RestApi.Databases.zip'
                }
            }
            stage('UnzipDatabaseArchive') {
                dir('Archive/EdFi.RestApi.Databases') {
                    unzip zipFile: 'EdFi.RestApi.Databases.zip'
                }
            }
            stage('DeployDatabases') {
                dir ('Archive') {
                    def status = powershell returnStatus: true, script: "./ConfigureBulkLoadConsole.ps1 -server \"${dbServer}\""
                    if (status != 0) {
                        error("Configure bulk load console application failed.")
                    }
                    status = powershell returnStatus: true, script: "./ConfigureDatabaseDeploy.ps1 -server \"${dbServer}\""
                    if (status != 0) {
                        error("Configure database deploy failed.")
                    }
                    if (params.installType == 'Sandbox' && params.dbServer != "(local)" && params.dbServer != "." && params.dbServer != "localhost") {
                        status = powershell returnStatus: true, script: "./CopyDbBackupToServerShare.ps1 -dbServer \"${dbServer}\" -dbBackupDir \"${dbBackupDir}\""
                        if (status != 0) {
                            error("Configure database deploy failed.")
                        }
                    }
                    status = powershell returnStatus: true, script: "./EdFi.RestApi.Databases/PostDeploy.ps1 -PathResolverRepositoryOverride \"Ed-Fi-Common;Ed-Fi-ODS;Ed-Fi-ODS-Implementation\" -InstallType \"${installType}\" -OdsYears \"${odsYears}\" -Transient \"${trans}\" -ExcludedExtensionSources \"${ExcludedExtensionSources}\" -EnabledFeatureNames \"\""
                    if (status != 0) {
                        error("Deploy Ed-Fi ODS databases failed.")
                    }
                }
            }
        }
    },
    'DeployToWeb': {
        node('master') {
            stage('PullArtifactsWeb') {
                copyArtifacts(projectName: "${projectName}", flatten: true, target: "Archive")
            }
            stage('DeployWebSites') {
                if (params.installType == 'SharedInstance') {
                    bat "echo Deploying Shared Instance"
                    if (params.isProd != "true") {
                        bat "\"C:/Program Files/IIS/Microsoft Web Deploy V3/msdeploy.exe\" -verb:sync -source:package=\"Archive/EdFi.Ods.SwaggerUI.zip\" -dest:auto,computerName=\"${webServer}\",includeAcls=False -setParam:name=\"IIS Web Application Name\",value=\"Default Web Site/EdFi.Ods.SwaggerUI\" -setParam:name=\"WebServer\",value=\"${proxyServer}\" -setParam:name=\"WebApi\",value=\"EdFi.Ods.WebApi\""
                    }
                    bat "\"C:/Program Files/IIS/Microsoft Web Deploy V3/msdeploy.exe\" -verb:sync -source:package=\"Archive/EdFi.Ods.WebApi.zip\" -dest:auto,computerName=\"${webServer}\",includeAcls=False -setParam:name=\"IIS Web Application Name\",value=\"Default Web Site/EdFi.Ods.WebApi\" -setParam:name=\"DatabaseServer\",value=\"${dbServer}\"  -setParam:name=\"OdsType\",value=\"${odsType}\""
                    def odsYear;
                    if (params.odsYears.contains(';')) {
                        odsYear = '_' + params.odsYears.split(';')[0];
                    } else {
                        odsYear = '_' + params.odsYears;
                    }
                    bat "echo using ODS Year: $odsYear"
                    bat "\"C:/Program Files/IIS/Microsoft Web Deploy V3/msdeploy.exe\" -verb:sync -source:package=\"Archive/EdFi.Ods.SecurityConfiguration.Administration.Web.zip\" -dest:auto,computerName=\"${webServer}\",includeAcls=False  -setParam:name=\"IIS Web Application Name\",value=\"Default Web Site/EdFi.Ods.SecurityConfiguration.Administration.Web\" -setParam:name=\"DatabaseServer\",value=\"${dbServer}\" -setParam:name=\"OdsYear\",value=\"${odsYear}\" -setParam:name=\"WebServer\",value=\"${proxyServer}\" -setParam:name=\"KeyRetrieval\",value=\"https:\\\\mn-dev\\2EdFi.Ods.SecurityConfiguration.KeyRetrieval.Web\" -setParam:name=\"FromAddress\",value=\"${fromAddress}\" -setParam:name=\"Host\",value=\"${host}\" -setParam:name=\"UserName\",value=\"${userName}\" -setParam:name=\"Password\",value=\"${password}\" -setParam:name=\"Port\",value=\"${port}\" -setParam:name=\"EnableSsl\",value=\"${enableSsl}\""
                    bat "\"C:/Program Files/IIS/Microsoft Web Deploy V3/msdeploy.exe\" -verb:sync -source:package=\"Archive/EdFi.Ods.SecurityConfiguration.KeyRetrieval.Web.zip\" -dest:auto,computerName=\"${webServer}\",includeAcls=False  -setParam:name=\"IIS Web Application Name\",value=\"Default Web Site/EdFi.Ods.SecurityConfiguration.KeyRetrieval.Web\" -setParam:name=\"DatabaseServer\",value=\"${dbServer}\""
                } else {
                    bat "echo Deploying Sandbox"
                    bat "\"C:/Program Files/IIS/Microsoft Web Deploy V3/msdeploy.exe\" -verb:sync -source:package=\"Archive/EdFi.Ods.Admin.Web.zip\" -dest:auto,computerName=\"${webServer}\",includeAcls=False -setParam:name=\"IIS Web Application Name\",value=\"Default Web Site/EdFi.Ods.Admin.Web\" -setParam:name=\"DatabaseServer\",value=\"${dbServer}\" -setParam:name=\"WebServer\",value=\"${proxyServer}\" -setParam:name=\"WebApi\",value=\"EdFi.Ods.WebApi\" -setParam:name=\"AdminInitialization\",value=\"${adminInit}\" -setParam:name=\"AdminPassword\",value=\"${adminPwd}\" -setParam:name=\"FromAddress\",value=\"${fromAddress}\" -setParam:name=\"Host\",value=\"${host}\" -setParam:name=\"UserName\",value=\"${userName}\" -setParam:name=\"Password\",value=\"${password}\" -setParam:name=\"Port\",value=\"${port}\" -setParam:name=\"EnableSsl\",value=\"${enableSsl}\""
                    bat "\"C:/Program Files/IIS/Microsoft Web Deploy V3/msdeploy.exe\" -verb:sync -source:package=\"Archive/EdFi.Ods.SwaggerUI.zip\" -dest:auto,computerName=\"${webServer}\",includeAcls=False -setParam:name=\"IIS Web Application Name\",value=\"Default Web Site/EdFi.Ods.SwaggerUI\" -setParam:name=\"WebServer\",value=\"${proxyServer}\" -setParam:name=\"WebApi\",value=\"https:\\\\mndev2\\EdFi.Ods.WebApi\\metadata\""
                    bat "\"C:/Program Files/IIS/Microsoft Web Deploy V3/msdeploy.exe\" -verb:sync -source:package=\"Archive/EdFi.Ods.WebApi.zip\" -dest:auto,computerName=\"${webServer}\",includeAcls=False -setParam:name=\"IIS Web Application Name\",value=\"Default Web Site/EdFi.Ods.WebApi\" -setParam:name=\"DatabaseServer\",value=\"${dbServer}\"  -setParam:name=\"OdsType\",value=\"${odsType}\""
                }
            }
        }
}
