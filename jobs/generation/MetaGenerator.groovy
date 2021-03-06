// This project will generate new job generators based on changes to the repolist.txt, which will
// in turn cause those generators to run over the job generation in the local repos.

import jobs.generation.Utilities

// About this generator:
//
//  This generator is designed to run every time there is a change to
//    * The dotnet-ci repo's generation dir
//    
//  A rerun of this could mean any number of things:
//    * Updated repolist.txt
//    * Updated utilities
//
//  Rerunning this generator will not automatically rerun all of the generators
//  below it (by design).  Users making changes to the overall generation structure
//  should recognize when there is a functional change being made and rerun all of the
//  generated jobs.

streamFileFromWorkspace('dotnet-ci/jobs/data/repolist.txt').eachLine { line ->
    // Skip comment lines
    boolean skip = (line ==~ / *#.*/);
    line.trim()
    skip |= (line == '')
    if (!skip) {
        // Tokenize the line into columns.  If there is a second column, it is the root folder that the
        // the jobs should go in (vs. the repo name)
        def projectInfo = line.tokenize()
        assert projectInfo.size() == 1 || projectInfo.size() == 2 : "Line ${line} should have at least a project name"
        def project = projectInfo[0]
       	// Create a folder for the project
        def generatorFolder = Utilities.getFolderName(project)
        if (projectInfo.size() == 2) {
            generatorFolder = projectInfo[1]
            if (generatorFolder == '<root>') {
                generatorFolder = ''
            }
        }
        
        def generatorPRTestFolder = "${generatorFolder}/GenPRTest"
        
        if (generatorFolder != '') {
      	  folder(generatorFolder) {}
        }
        
        // Create a Folder for generator PR tests under that.
        folder(generatorPRTestFolder) {}
        
        [true, false].each { isPRTest ->
            def jobGenerator = job(Utilities.getFullJobName(project, 'generator', isPRTest, isPRTest ? generatorPRTestFolder : generatorFolder)) {
                // Need multiple scm's
                multiscm {
                    git {
                        remote {
                            github('dotnet/dotnet-ci')
                        }
                        relativeTargetDir('dotnet-ci')
                        branch('*/master')
                    }
                    // 
                    git {
                        remote {
                            github(project)
                            
                            if (isPRTest) {
                                refspec('+refs/pull/*:refs/remotes/origin/pr/*')
                            }
                        }
                        // Want the relative to be just the project name
                        relativeTargetDir(Utilities.getProjectName(project))
                        
                        // If PR, change to ${sha1} 
                        if (isPRTest) {
                            branch('${sha1}')
                        }
                        else {
                            branch('*/master')
                        }
                    }
                }
                
                // Add a parameter for the project, so that gets passed to the
                // netci.groovy file
                parameters {
                    stringParam('GithubProject', project, 'Project name passed to the netci generator')
                }
                
                // Add in the job generator logic
                
                steps {
                    dsl {
                        // Loads netci.groovy
                        external(Utilities.getProjectName(project) + '/netci.groovy')
                        
                        // Additional classpath should point to the utility repo
                        additionalClasspath('dotnet-ci')
                        
                        // Generate jobs relative to the seed job.        
                        lookupStrategy('SEED_JOB')
                        
                        // PR tests should do nothing with the other jobs.
                        // Non-PR tests should disable the jobs, which will get cleaned
                        // up later.
                        if (isPRTest) {
                            removeAction('IGNORE')
                        }
                        else {
                            removeAction('DISABLE')
                        }
                        removeViewAction('DELETE')
                    }
                    
                    // If this is a PR test job, we don't want the generated jobs
                    // to actually trigger (say on a github PR, since that will be confusing
                    // and wasteful.  We can accomplish this by adding another DSL step that does
                    // nothing.  It will generate no jobs, but the remove action is DISABLE so the
                    // jobs generated in the previous step will be disabled.
                    
                    if (isPRTest) {
                        dsl {
                             text('// Generate no jobs so the previously generated jobs are disabled')
                        
                             // Generate jobs relative to the seed job.        
                             lookupStrategy('SEED_JOB')
                             removeAction('DISABLE')
                             removeViewAction('DELETE')
                        }
                    }
                }
                
                // Enable concurrent builds 
                concurrentBuild()

                // Enable the log rotator

                logRotator {    
                    artifactDaysToKeep(7)
                    daysToKeep(21)
                    artifactNumToKeep(25)
                }
            }
            
            // jobGenerator.with {
                // Disable concurrency
                // concurrentBuild(false)
                
                // Disable concurrency across all generators 
                // throttleConcurrentBuilds {
                //  throttleDisabled(false)
                //  maxTotal(1)
                //  maxPerNode(1)
                //  categories(['job_generators'])
                // }
            // }
            
            if (isPRTest) {
                // Enable the github PR trigger, but add a trigger phrase so
                // that it doesn't build on every change.
                Utilities.addGithubPRTrigger(jobGenerator, jobGenerator.name, '(?i).*test\\W+ci\\W+please.*')
            }
            else {
                // Enable the github push trigger
                Utilities.addGithubPushTrigger(jobGenerator)
            }
        }
    }
}
