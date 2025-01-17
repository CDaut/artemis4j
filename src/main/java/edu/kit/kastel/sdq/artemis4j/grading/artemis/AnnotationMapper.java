/* Licensed under EPL-2.0 2022-2023. */
package edu.kit.kastel.sdq.artemis4j.grading.artemis;

import edu.kit.kastel.sdq.artemis4j.api.artemis.Exercise;
import edu.kit.kastel.sdq.artemis4j.api.artemis.User;
import edu.kit.kastel.sdq.artemis4j.api.artemis.assessment.*;
import edu.kit.kastel.sdq.artemis4j.api.grading.IAnnotation;
import edu.kit.kastel.sdq.artemis4j.api.grading.IMistakeType;
import edu.kit.kastel.sdq.artemis4j.api.grading.IRatingGroup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.NumberFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Maps Annotations to Artemis-accepted json-formatted strings.
 */
public class AnnotationMapper {
	// keep this up to date with
	// https://github.com/ls1intum/Artemis/blob/develop/src/main/java/de/tum/in/www1/artemis/config/Constants.java#L121
	private static final int FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS = 5000;

	// amount of space to leave in the feedback-text
	private static final int FEEDBACK_DETAIL_SAFETY_MARGIN = 50;

	private static final NumberFormat nf = new DecimalFormat("##.###", DecimalFormatSymbols.getInstance(Locale.ENGLISH));

	private static final Logger log = LoggerFactory.getLogger(AnnotationMapper.class);

	private final ObjectMapper oom = new ObjectMapper();

	private final Exercise exercise;
	private final Submission submission;

	private final List<IAnnotation> annotations;

	private final List<IRatingGroup> ratingGroups;
	private final User assessor;

	private final LockResult lock;

	public AnnotationMapper(Exercise exercise, Submission submission, List<IAnnotation> annotations, List<IRatingGroup> ratingGroups, User assessor,
			LockResult lock) {
		this.exercise = exercise;
		this.submission = submission;

		this.annotations = annotations;
		this.ratingGroups = ratingGroups;
		this.assessor = assessor;
		this.lock = lock;
	}

	private double calculateAbsoluteScore(List<Feedback> allFeedbacks) {
		return allFeedbacks.stream().mapToDouble(Feedback::getCredits).sum();
	}

	private List<Feedback> calculateAllFeedbacks() throws IOException {
		final List<Feedback> result = new ArrayList<>(this.getFilteredPreexistentFeedbacks());
		result.addAll(this.calculateManualFeedbacks());
		result.addAll(this.calculateAnnotationSerialitationAsFeedbacks());
		result.removeIf(Objects::isNull);
		return result;
	}

	private List<Feedback> calculateAnnotationSerialisationAsFeedbacks(List<IAnnotation> givenAnnotations, int detailTextMaxCharacters) throws IOException {
		final String givenAnnotationsJSONString = this.convertAnnotationsToJSONString(givenAnnotations);
		// put as many feedbacks in one pack.
		if (givenAnnotationsJSONString.length() < detailTextMaxCharacters) {
			// we don't want the serialization to be visible (for non-privileged users)
			return List.of(new Feedback(FeedbackType.MANUAL_UNREFERENCED.name(), 0D, null, null, "NEVER", "CLIENT_DATA", null, givenAnnotationsJSONString));
		}
		// if one single annotation is too large, serialization is impossible!
		if (givenAnnotations.size() == 1) {
			throw new IOException("This annotation is too large to serialize! " + givenAnnotationsJSONString);
		}

		// recursion
		final int givenAnnotationsSize = givenAnnotations.size();
		final List<Feedback> resultFeedbacks = new ArrayList<>(
				this.calculateAnnotationSerialisationAsFeedbacks(givenAnnotations.subList(0, givenAnnotationsSize / 2), detailTextMaxCharacters));
		resultFeedbacks.addAll(this.calculateAnnotationSerialisationAsFeedbacks(givenAnnotations.subList(givenAnnotationsSize / 2, givenAnnotations.size()),
				detailTextMaxCharacters));
		return resultFeedbacks;
	}

	private List<Feedback> calculateAnnotationSerialitationAsFeedbacks() throws IOException {
		// because Artemis has a Limit on "detailText" of 5000, we gotta do this little
		// trick
		return this.calculateAnnotationSerialisationAsFeedbacks(new ArrayList<>(this.annotations), FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS);
	}

	private List<Feedback> calculateManualFeedbacks() {
		List<Feedback> manualFeedbacks = new ArrayList<>(this.annotations.stream().collect(Collectors.groupingBy(IAnnotation::getStartLine)).entrySet().stream()
				.map(this::createInlineFeedbackWithNoDeduction).toList());
		// add the (rated!) rating group annotations
		this.ratingGroups.forEach(group -> manualFeedbacks.addAll(this.createGlobalFeedbackWithDeduction(group)));
		return manualFeedbacks;
	}

	private double calculateRelativeScore(double absoluteScore) {
		return absoluteScore / this.exercise.getMaxPoints() * 100D;
	}

	private String convertAnnotationsToJSONString(final List<IAnnotation> givenAnnotations) throws JsonProcessingException {
		return oom.writeValueAsString(givenAnnotations);
	}

	/**
	 * This transforms Annotations (in the context of the whole model, consisting of
	 * RatingGroupse, MistakteTypes etc) into a payload. In the process, calculation
	 * is done, including
	 * <ul>
	 * <li>calculating the rating score based on our annotations and the previously
	 * existent (automatic) feedbacks (e.g. Unit test results)
	 * <li>creating per-annotation artemis-annotations ("Feedbacks")
	 * {@link FeedbackType#MANUAL}
	 * <li>creating general artemis-annotations ("Feedbacks")
	 * {@link FeedbackType#MANUAL_UNREFERENCED}
	 * <li>creating our own database by serializing our Java Annotations into HIDDEN
	 * {@link FeedbackType#MANUAL_UNREFERENCED} Feedbacks with
	 * <ul>
	 * <li>"CLIENT_DATA" in the <I>text</I> field, as an identifier
	 * <li>the Java Annotations as json blob in the <I>detailText</I> field.
	 * </ul>
	 * </ul>
	 *
	 * @return a json-formattable object ready to be send as payload to the Client
	 */
	public AssessmentResult createAssessmentResult() throws IOException {
		// only add preexistent automatic feedback (unit tests etc) and manual feedback.
		// this should work indepently of invalid or not. if invalid, there should just
		// be no feedbacks.
		final List<Feedback> allFeedbacks = this.calculateAllFeedbacks();

		// Cap to [0, maxPoints]
		final double absoluteScore = Math.min(Math.max(0.D, this.calculateAbsoluteScore(allFeedbacks)), this.exercise.getMaxPoints());
		final double relativeScore = this.calculateRelativeScore(absoluteScore);

		final List<Feedback> initialFeedback = getFilteredPreexistentFeedbacks();
		final List<Feedback> tests = initialFeedback.stream().filter(f -> f.getReference() == null).toList();

		int codeIssueCount = (int) initialFeedback.stream().filter(Feedback::isStaticCodeAnalysis).count();
		int passedTestCaseCount = (int) tests.stream() //
				.filter(feedback -> feedback.getPositive() != null && feedback.getPositive()).count();

		return new AssessmentResult(this.submission.getSubmissionId(), "SEMI_AUTOMATIC", //
				relativeScore, true, true, this.assessor, allFeedbacks, //
				codeIssueCount, passedTestCaseCount, tests.size() //
		);
	}

	/**
	 * Creates the inlined feedbacks within Artemis
	 *
	 * @param annotations an entry contains the line number (indexed by 0) and all
	 *                    annotations starting in that line
	 * @return one feedback object for the line
	 */
	private Feedback createInlineFeedbackWithNoDeduction(Map.Entry<Integer, List<IAnnotation>> annotations) {
		int line = annotations.getKey();
		var sampleAnnotation = annotations.getValue().get(0);

		// Lines are indexed at 0
		final String text = "File " + sampleAnnotation.getClassFilePath() + " at line " + (line + 1);
		final String reference = "file:" + sampleAnnotation.getClassFilePath() + ".java_line:" + line;

		StringBuilder resultText = new StringBuilder();
		for (var annotation : annotations.getValue()) {
			var mistakeType = annotation.getMistakeType();
			String detailText = "[" + mistakeType.getRatingGroup().getDisplayName(null) + ":" + mistakeType.getButtonText(null) + "] ";
			if (mistakeType.isCustomPenalty()) {
				detailText += annotation.getCustomMessage().orElseThrow() + " (" + nf.format(annotation.getCustomPenalty().orElseThrow()) + "P)";
			} else {
				detailText += mistakeType.getMessage(null);
				if (annotation.getCustomMessage().isPresent()) {
					detailText += "\nExplanation: " + annotation.getCustomMessage().orElseThrow();
				}
			}
			resultText.append(detailText).append("\n\n");
		}
		return new Feedback(FeedbackType.MANUAL.name(), 0D, null, null, null, text, reference, resultText.toString().trim());
	}

	private List<Feedback> createGlobalFeedbackWithDeduction(IRatingGroup ratingGroup) {
		final PointResult pointResult = calculatePointsForRatingGroup(ratingGroup);
		final var range = ratingGroup.getRange();

		List<String> lines = new ArrayList<>();

		String annotationHeadline = "";

		annotationHeadline = ratingGroup.getDisplayName(null) + " [" + nf.format(pointResult.points);

		if (!range.isEmpty()) {
			double lower = range.first() == null ? Double.NEGATIVE_INFINITY : range.first();
			double upper = range.second() == null ? Double.POSITIVE_INFINITY : range.second();

			annotationHeadline += " (Range: " + nf.format(lower) + " -- " + nf.format(upper) + ")";
		}

		annotationHeadline += " points]";

		for (var mistakeTypeXScore : pointResult.scores.entrySet()) {
			final var mistakeType = mistakeTypeXScore.getKey();
			final double currentPenalty = mistakeTypeXScore.getValue();

			final List<IAnnotation> currentAnnotations = this.annotations.stream() //
					.filter(annotation -> annotation.getMistakeType().equals(mistakeType)) //
					.toList();
			lines.add("\n    * \"" + mistakeType.getButtonText(null) + "\" [" + nf.format(currentPenalty) + "P]:");
			if (mistakeType.isCustomPenalty()) {
				for (var annotation : currentAnnotations) {
					String penalty = nf.format(annotation.getCustomPenalty().orElseThrow());
					lines.add("\n        * " + annotation.getClassFilePath() + " at line " + (annotation.getStartLine() + 1) + " (" + penalty + "P)");
				}
			} else {
				for (var annotation : currentAnnotations) {
					lines.add("\n        * " + annotation.getClassFilePath() + " at line " + (annotation.getStartLine() + 1));
				}
			}
		}

		if (pointResult.reachedLimit) {
			lines.add("\n    * Note: The sum of penalties hit the limits for this rating group.");
		}

		List<String> feedbackTexts = new LinkedList<>();

		if (lines.isEmpty()) {
			return List.of();
		}

		StringBuilder text = new StringBuilder(annotationHeadline + " (annotation " + 1 + ")");

		for (String line : lines) {
			if (text.length() + line.length() >= FEEDBACK_DETAIL_TEXT_MAX_CHARACTERS - annotationHeadline.length() - FEEDBACK_DETAIL_SAFETY_MARGIN) {
				feedbackTexts.add(text.toString());
				text = new StringBuilder(annotationHeadline + " (annotation " + (feedbackTexts.size() + 1) + ")");
			}
			text.append(line);
		}
		feedbackTexts.add(text.toString());

		List<Feedback> feedbacks = new LinkedList<>();

		feedbacks.add(new Feedback(FeedbackType.MANUAL_UNREFERENCED.name(), pointResult.points, null, null, null, null, null, feedbackTexts.get(0)));

		for (int i = 1; i < feedbackTexts.size(); i++) {
			feedbacks.add(new Feedback(FeedbackType.MANUAL_UNREFERENCED.name(), 0d, null, null, null, null, null, feedbackTexts.get(i)));
		}

		return feedbacks;
	}

	private List<Feedback> getFilteredPreexistentFeedbacks() {
		List<Feedback> feedbacks = new ArrayList<>();
		for (Feedback feedback : this.lock.getLatestFeedback()) {
			if (feedback.getFeedbackType() == null || feedback.getFeedbackType() != FeedbackType.AUTOMATIC) {
				continue;
			}
			feedbacks.add(feedback);
		}
		return feedbacks;
	}

	public PointResult calculatePointsForRatingGroup(IRatingGroup ratingGroup) {
		// Calculate the points w.r.t. the PenaltyTypes
		if (log.isInfoEnabled())
			log.info("Calculate Points for RG {}", ratingGroup.getDisplayName(null));
		double sum = 0;
		Map<IMistakeType, Double> scores = new HashMap<>();
		for (var mistakeType : ratingGroup.getMistakeTypes()) {
			Double score = calculatePointsForMistakeType(mistakeType);
			if (score == null) {
				// No annotation made.
				continue;
			}
			scores.put(mistakeType, score);
			sum += score;
		}

		boolean reachedLimit = !ratingGroup.getRange().isEmpty() && ratingGroup.setToRange(sum) != sum;
		if (reachedLimit) {
			if (log.isInfoEnabled())
				log.info("RG {} reached limit", ratingGroup.getDisplayName(null));
			sum = ratingGroup.setToRange(sum);
		}
		return new PointResult(sum, reachedLimit, scores);
	}

	private Double calculatePointsForMistakeType(IMistakeType mistakeType) {
		if (log.isInfoEnabled())
			log.info("Calculate Points for MT {}", mistakeType.getButtonText(null));
		var filteredAnnotations = this.annotations.stream().filter(a -> a.getMistakeType().equals(mistakeType)).toList();
		if (filteredAnnotations.isEmpty()) {
			return null;
		}

		var points = mistakeType.calculate(filteredAnnotations);
		if (log.isInfoEnabled())
			log.info("MT {} -> {}", mistakeType.getButtonText(null), points);
		return points;
	}

	public record PointResult(double points, boolean reachedLimit, Map<IMistakeType, Double> scores) {
	}

}
