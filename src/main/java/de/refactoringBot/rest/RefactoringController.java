package de.refactoringBot.rest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import de.refactoringBot.api.main.ApiGrabber;
import de.refactoringBot.api.sonarQube.SonarQubeDataGrabber;
import de.refactoringBot.configuration.BotConfiguration;
import de.refactoringBot.controller.main.BotController;
import de.refactoringBot.controller.main.GitController;
import de.refactoringBot.controller.main.GrammarController;
import de.refactoringBot.controller.sonarQube.SonarQubeObjectTranslator;
import de.refactoringBot.model.botIssue.BotIssue;
import de.refactoringBot.model.configuration.ConfigurationRepository;
import de.refactoringBot.model.configuration.GitConfiguration;
import de.refactoringBot.model.exceptions.BotRefactoringException;
import de.refactoringBot.model.outputModel.botPullRequest.BotPullRequest;
import de.refactoringBot.model.outputModel.botPullRequest.BotPullRequests;
import de.refactoringBot.model.outputModel.botPullRequestComment.BotPullRequestComment;
import de.refactoringBot.model.refactoredIssue.RefactoredIssue;
import de.refactoringBot.model.refactoredIssue.RefactoredIssueRepository;
import de.refactoringBot.refactoring.RefactoringPicker;
import io.swagger.annotations.ApiOperation;

/**
 * This REST-Controller creates an REST-API which allows the user to perform
 * refactorings with the bot.
 * 
 * @author Stefan Basaric
 *
 */
@RestController
@RequestMapping(path = "/refactorings")
public class RefactoringController {

	@Autowired
	ApiGrabber grabber;
	@Autowired
	SonarQubeDataGrabber sonarCubeGrabber;
	@Autowired
	GitController dataGetter;
	@Autowired
	ConfigurationRepository configRepo;
	@Autowired
	RefactoredIssueRepository issueRepo;
	@Autowired
	RefactoredIssueRepository refactoredIssues;
	@Autowired
	BotConfiguration botConfig;
	@Autowired
	BotController botController;
	@Autowired
	GrammarController grammarController;
	@Autowired
	RefactoringPicker refactoring;
	@Autowired
	SonarQubeObjectTranslator sonarTranslator;
	
	// Logger
    private static final Logger logger = LoggerFactory.getLogger(RefactoringController.class);

	/**
	 * This method performs refactorings with comments within Pull-Requests of a
	 * Filehoster like GitHub.
	 * 
	 * @param configID
	 * @return allRequests
	 */
	@RequestMapping(value = "/refactorWithComments/{configID}", method = RequestMethod.GET, produces = "application/json")
	@ApiOperation(value = "Perform refactorings with Pull-Request-Comments.")
	public ResponseEntity<?> refactorWithComments(@PathVariable Long configID) {

		// Create empty list of refactored Issues
		List<RefactoredIssue> allRefactoredIssues = new ArrayList<RefactoredIssue>();
		Integer amountBotRequests = 0;

		Optional<GitConfiguration> gitConfig;
		try {
			// Try to get the Git-Configuration with the given ID
			gitConfig = configRepo.getByID(configID);
		} catch (Exception e) {
			// Print exception and abort if database error occurs
			logger.error(e.getMessage(), e);
			return new ResponseEntity<String>("Connection with database failed!", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		// If Configuration does not exist
		if (!gitConfig.isPresent()) {
			return new ResponseEntity<String>("Configuration with given ID does not exist!", HttpStatus.NOT_FOUND);
		}

		// Init translated Pull-Request-Object
		BotPullRequests allRequests = null;
		try {
			// Fetch target-Repository-Data
			dataGetter.fetchRemote(gitConfig.get());
			// Get Pull-Requests with comments
			allRequests = grabber.getRequestsWithComments(gitConfig.get());
			amountBotRequests = botController.getAmountOfBotRequests(allRequests, gitConfig.get());

			// Check if max amount is reached
			if (amountBotRequests >= gitConfig.get().getMaxAmountRequests()) {
				return new ResponseEntity<String>(
						"Maximal amount of requests reached." + "(Maximum = " + gitConfig.get().getMaxAmountRequests()
								+ "; Currently = " + amountBotRequests + " bot requests are open)",
						HttpStatus.BAD_REQUEST);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}

		// Iterate through all requests
		for (BotPullRequest request : allRequests.getAllPullRequests()) {
			// Iterate through all comments
			for (BotPullRequestComment comment : request.getAllComments()) {

				// When Bot-Pull-Request-Limit reached -> return
				if (amountBotRequests >= gitConfig.get().getMaxAmountRequests()) {
					// Return all refactored issues
					return new ResponseEntity<List<RefactoredIssue>>(allRefactoredIssues, HttpStatus.OK);
				}

				// Check if comment is valid and not already refactored
				if (grammarController.checkComment(comment.getCommentBody()) && !issueRepo
						.refactoredComment(gitConfig.get().getRepoService(), comment.getCommentID().toString())
						.isPresent()) {
					// Create issue
					BotIssue botIssue;
					try {
						botIssue = grammarController.createIssueFromComment(comment);
					} catch (Exception e) {
						logger.error(e.getMessage(), e);
						return new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
					}

					try {
						// For Requests created by someone else
						if (!request.getCreatorName().equals(gitConfig.get().getBotName())) {
							// Create refactoring branch with Filehoster-Service + Comment-ID
							String newBranch = gitConfig.get().getRepoService() + "_Refactoring_"
									+ comment.getCommentID().toString();
							// Check if branch already exists (throws exception if it does)
							grabber.checkBranch(gitConfig.get(), newBranch);
							// Create new Branch
							dataGetter.createBranch(gitConfig.get(), request.getBranchName(), newBranch);

							// Try to refactor
							botIssue.setCommitMessage(refactoring.pickAndRefactor(botIssue, gitConfig.get()));

							// If successful
							if (botIssue.getCommitMessage() != null) {
								// Create Refactored-Obect
								RefactoredIssue refactoredIssue = botController.buildRefactoredIssue(botIssue,
										gitConfig.get());

								// Push changes + create Pull-Request
								dataGetter.pushChanges(gitConfig.get(), botIssue.getCommitMessage());
								grabber.makeCreateRequest(request, comment, gitConfig.get(), newBranch);

								// Save to Database + add to list
								RefactoredIssue savedIssue = refactoredIssues.save(refactoredIssue);
								allRefactoredIssues.add(savedIssue);
								amountBotRequests++;
							}
							// For Requests created by the bot
						} else {
							// Change to existing Refactoring-Branch
							dataGetter.switchBranch(gitConfig.get(), request.getBranchName());

							// Try to refactor
							botIssue.setCommitMessage(refactoring.pickAndRefactor(botIssue, gitConfig.get()));

							// If successful
							if (botIssue.getCommitMessage() != null) {
								// Create Refactored-Object
								RefactoredIssue refactoredIssue = botController.buildRefactoredIssue(botIssue,
										gitConfig.get());

								// Push changes
								dataGetter.pushChanges(gitConfig.get(), botIssue.getCommitMessage());
								// Reply to User
								grabber.replyToUserInsideBotRequest(request, comment, gitConfig.get());

								// Save to Database + add to list
								RefactoredIssue savedIssue = refactoredIssues.save(refactoredIssue);
								allRefactoredIssues.add(savedIssue);
							}
						}
						// Catch refactoring errors
					} catch (BotRefactoringException e) {
						// Create failed Refactored-Object
						botIssue.setErrorMessage(e.getMessage());
						RefactoredIssue failedIssue = botController.buildRefactoredIssue(botIssue, gitConfig.get());

						// Reply to user
						try {
							grabber.replyToUserForFailedRefactoring(request, comment, gitConfig.get(),
									botIssue.getErrorMessage());
						} catch (Exception u) {
							logger.error(u.getMessage(), u);
						}

						// Save failed refactoring to database + add to list
						RefactoredIssue savedFailIssue = refactoredIssues.save(failedIssue);
						allRefactoredIssues.add(savedFailIssue);
						logger.error(e.getMessage(), e);

						// Catch other errors
					} catch (Exception e) {
						// Create failed Refactored-Object
						botIssue.setErrorMessage("Bot could not refactor this comment! Internal server error!");
						RefactoredIssue failedIssue = botController.buildRefactoredIssue(botIssue, gitConfig.get());

						// Reply to user
						try {
							grabber.replyToUserForFailedRefactoring(request, comment, gitConfig.get(),
									botIssue.getErrorMessage());
						} catch (Exception u) {
							logger.error(u.getMessage(), u);
						}

						// Save failed refactoring to database + add to list
						RefactoredIssue savedFailIssue = refactoredIssues.save(failedIssue);
						allRefactoredIssues.add(savedFailIssue);
						logger.error(e.getMessage(), e);
					}
				}
			}
		}

		// Return all refactored issues
		return new ResponseEntity<List<RefactoredIssue>>(allRefactoredIssues, HttpStatus.OK);
	}

	/**
	 * This method performs refactorings according to findings with an analysis
	 * service like SonarCube.
	 * 
	 * @param configID
	 * @return allRefactoredIssues
	 */
	@RequestMapping(value = "/refactorWithAnalysisService/{configID}", method = RequestMethod.GET, produces = "application/json")
	@ApiOperation(value = "Perform refactorings with analysis service.")
	public ResponseEntity<?> refactorWithSonarCube(@PathVariable Long configID) {

		// Create empty list of refactored Issues
		List<RefactoredIssue> allRefactoredIssues = new ArrayList<RefactoredIssue>();
		Integer amountBotRequests = 0;

		Optional<GitConfiguration> gitConfig;
		try {
			// Try to get the Git-Configuration with the given ID
			gitConfig = configRepo.getByID(configID);
		} catch (Exception e) {
			// Print exception and abort if database error occurs
			logger.error(e.getMessage(), e);
			return new ResponseEntity<String>("Connection with database failed!", HttpStatus.INTERNAL_SERVER_ERROR);
		}
		// If configuration does not exist
		if (!gitConfig.isPresent()) {
			return new ResponseEntity<String>("Configuration with the given ID does not exist!", HttpStatus.NOT_FOUND);
		}
		// If analysis service data is missing
		if (gitConfig.get().getAnalysisService() == null || gitConfig.get().getAnalysisServiceProjectKey() == null) {
			return new ResponseEntity<String>("Configuration is missing analysis service data!",
					HttpStatus.BAD_REQUEST);
		}

		try {
			// Fetch target-Repository-data
			dataGetter.fetchRemote(gitConfig.get());
			// Get Pull-Requests with comments
			BotPullRequests allRequests = grabber.getRequestsWithComments(gitConfig.get());
			amountBotRequests = botController.getAmountOfBotRequests(allRequests, gitConfig.get());

			// Check if max amount is reached
			if (amountBotRequests >= gitConfig.get().getMaxAmountRequests()) {
				return new ResponseEntity<String>(
						"Maximal amount of requests reached." + "(Maximum = " + gitConfig.get().getMaxAmountRequests()
								+ "; Currently = " + amountBotRequests + " bot requests are open)",
						HttpStatus.BAD_REQUEST);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}

		try {
			// Get issues from analysis service API
			List<BotIssue> botIssues = grabber.getAnalysisServiceIssues(gitConfig.get());

			// If analysis-service not supported
			if (botIssues == null) {
				return new ResponseEntity<String>(
						"Analysis-Service '" + gitConfig.get().getAnalysisService() + "' is not supported!",
						HttpStatus.BAD_REQUEST);
			}

			// Iterate all issues
			for (BotIssue botIssue : botIssues) {

				// When Bot-Pull-Request-Limit reached -> return
				if (amountBotRequests >= gitConfig.get().getMaxAmountRequests()) {
					// Return all refactored issues
					return new ResponseEntity<List<RefactoredIssue>>(allRefactoredIssues, HttpStatus.OK);
				}

				try {
					// If issue was not already refactored
					if (!issueRepo.refactoredAnalysisIssue(botIssue.getCommentServiceID()).isPresent()) {
						// Create new branch for refactoring
						String newBranch = "sonarCube_Refactoring_" + botIssue.getCommentServiceID();
						// Check if branch already exists (throws exception if it does)
						grabber.checkBranch(gitConfig.get(), newBranch);
						dataGetter.createBranch(gitConfig.get(), "master", newBranch);
						// Try to refactor
						botIssue.setCommitMessage(refactoring.pickAndRefactor(botIssue, gitConfig.get()));

						// If successful
						if (botIssue.getCommitMessage() != null) {
							// Create Refactored-Object
							RefactoredIssue refactoredIssue = botController.buildRefactoredIssue(botIssue,
									gitConfig.get());

							// Push changes + create Pull-Request
							dataGetter.pushChanges(gitConfig.get(), botIssue.getCommitMessage());
							grabber.makeCreateRequestWithAnalysisService(botIssue, gitConfig.get(), newBranch);

							// Save to database + add to list
							RefactoredIssue savedIssue = refactoredIssues.save(refactoredIssue);
							allRefactoredIssues.add(savedIssue);

							amountBotRequests++;
						}
					}
				} catch (Exception e) {
					// Create failed Refactored-Object
					botIssue.setErrorMessage("Bot could not refactor this comment! Internal server error!");
					RefactoredIssue failedIssue = botController.buildRefactoredIssue(botIssue, gitConfig.get());

					// Save failed refactoring to database + add to list
					RefactoredIssue savedFailIssue = refactoredIssues.save(failedIssue);
					allRefactoredIssues.add(savedFailIssue);
					logger.error(e.getMessage(), e);
				}
			}

			return new ResponseEntity<List<RefactoredIssue>>(allRefactoredIssues, HttpStatus.OK);
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
			return new ResponseEntity<String>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
		}
	}
}
