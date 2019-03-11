#!groovy
/*
    Parameters:
    Name        Description
    projectName Name of the Jenkins project to supply the deployment artifacts
    dbServer    The database server
    dbBackupDir The location of the SQL Server backup folder on the dbServer
    installType SharedInstance or Sandbox
    odsYears    School Years represented e.g. 2017;2018
    trans       Are these databases going to be reset on each deploy? (transient is a keyword in Java/Groovy) true or false
    isProd      Is this a production deployment?
  transfer  Transfer Data from MDE-ORG into EducationOrganization tables
  applyEdOrgs Bulk Load Ed-Orgs to Year Specific ODS
*/
if (params.isProd == "true" && params.installType == "Sandbox") {
    error("Production deployment cannot be of type Sandbox.");
}
if (!params.odsYears?.trim()) {
    error("odsYears is a required parameter.");
}
node('master') {
    stage('RemovePreviousArtifacts') {
        def status = powershell returnStatus: true, script: '''
        $error.clear()
        if ((Test-Path -Path Archive)) {
            Remove-Item -Recurse -Force -Path Archive
        }
        if ($error.count -ge 1) { exit 1; }
        exit 0;
        '''
        if (status != 0) {
            error("Remove previous build artifacts failed.")
        }
    }
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
            status = powershell returnStatus: true, script: "./EdFi.RestApi.Databases/PostDeploy.ps1 -PathResolverRepositoryOverride \"Ed-Fi-Common;Ed-Fi-ODS;Ed-Fi-ODS-Implementation\" -InstallType \"${installType}\" -OdsYears \"${odsYears}\" -Transient \"${trans}\" -TransferData \"${transfer}\" -applyEdOrgs \"${applyEdOrgs}\" -ExcludedExtensionSources \"changeQueries\" -EnabledFeatureNames \"\""
                if (status != 0)
                error("Deploy Ed-Fi ODS databases failed.")
            }
        }
    }
