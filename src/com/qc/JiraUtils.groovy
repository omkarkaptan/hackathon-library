package com.qc; 

class JiraUtils implements Serializable {
    def build_number
    def ticket_number
    
    JiraUtils(build_number) {
        this.build_number = build_number
    }
    /* Initialize jira variables
     * - Add a new tag to the github repository
     * - Create a deployment ticket for the deployment
     * - Set the ticket to 'Begin Deployment'
     */
    def _initialize_jira_variables() {
        def new_tag = _tag_deployment()
        _create_deployment_ticket(new_tag)
        _transition('11') /* Transition Open -> Begin Deployment */
    }
    
    /* Create a new tag for the release and add it to the git repository */
    def _tag_deployment() {
        def new_tag = "v" + this.build_number
        withCredentials([[$class: 'UsernamePasswordMultiBinding',
                credentialsId: '3cf17090-f5eb-42b5-800c-b761b9e55085',
                usernameVariable: 'GIT_USERNAME',
                passwordVariable: 'GIT_PASSWORD']]) {
            sh("git tag " + new_tag)
            sh('git push https://${GIT_USERNAME}:${GIT_PASSWORD}@github.com/omkarkaptan/hackathon --tags')
        }
        
        return new_tag
    }
    
    /* Create deployment ticket with the necessary information
     * - This ticket will contain the git change log since the last tag (last attempted deployment)
     * - The ticket number will be stored in an environment variable visible only to the current pipeline run
     */
    def _create_deployment_ticket(new_tag) {
        def git_changelog = _get_git_changelog(new_tag)
        env.gitlog = git_changelog
        withEnv(['JIRA_SITE=JIRA']) {
            def deployment_ticket = [
                fields: [
                    project: [ key: 'TEST' ],
                    summary: 'Deployment ticket',
                    description: 'Release ' + new_tag + '. \nDeployment purpose here with deployment steps',
                    customfield_10053: 'Rollback instructions here',
                    customfield_10054: git_changelog,
                    customfield_10055: "${env.BUILD_URL}",
                    issuetype: [ id: 10005 ] /* Issue ID 10005 = Deployment Process */
                    
                ]
             ]
    
            response = jiraNewIssue issue: deployment_ticket
            this.ticket_number = response.data.key
        }
    }
    
    /* Create a git change log from the second tag to the current tag */
    def _get_git_changelog(new_tag) {
        def old_tag = sh (
            script: 'git describe --abbrev=0 --tags `git rev-list --tags --skip=1 --max-count=1`',
            returnStdout: true
        ).trim()
        
        def revision = sh (
            script: 'git log --pretty="|%h|%an|%s|%ae" ' + old_tag + '..' + new_tag,
            returnStdout: true
        ).trim()
        
        return revision
    }
    
    /* Transition a ticket from one state to another
     * The key here is to identify the ID of the transition from one state to another
     * rather than finding the ID of the target state itself.
     * 
     * The transition ID, although a number, should be passed as a string
     */
    def _transition(transition_id) {
        withEnv(['JIRA_SITE=JIRA']) {
            def transitionInput =
            [
                transition: [ id: transition_id ] /* transition = status1 -> status2 */
            ]
            
            jiraTransitionIssue idOrKey: this.ticket_number, input: transitionInput
        }
    }
    
    /* Post a comment to the JIRA ticket and also forward it to Slack */
    def _comment(comment_text) {
        withEnv(['JIRA_SITE=JIRA']) {
            jiraAddComment idOrKey: this.ticket_number, comment: comment_text
        }
        
        _post_to_slack(this.ticket_number + ": " + comment_text)
    }
    
    /* Post a message to slack */
    def _post_to_slack(slack_message) {
        slackSend channel: "#deployment", message: slack_message
    }
    
    /* Create a step that waits on a user input
     * 
     * The current implementation would most likely hold on to an executor till an input is given
     * It can be modified to time out after a while and fail. Leaving it this way for the purposes
     * of the hackathon
     */
    def _manual_promotion(display_message) {
        input message: display_message
    }
    
    /* Dummy functions containing deployment steps */
    def _deploy_to_stage() {
        echo 'Steps to deploy to stage'
    }
    
    def _deploy_to_production() {
        echo 'Steps to deploy to production'
    }
    /* ******************************************* */
    
    /* If a deployment succeeds
     * - transition to the validated stage
     * - comment on the ticket
     */
    def _successful_deployment() {
        _transition('31') /* Release to validated */
        _comment('Deployment to production validated')
    }
    
    /* If a deployment fails,
     * - transition the deployment ticket to rollback,
     * - add error details to ticket
     */
    def _failed_deployment(environment, exception) {
        
        if ( 'STAGE'.equalsIgnoreCase(environment) ) {
            _transition('41')
        } else if ( 'PRODUCTION'.equalsIgnoreCase(environment) ) {
            _transition('51')
        }
        _comment('Build failed while deploying to ' + environment + ' with error: ' + exception.toString())
        throw exception
    }
}
