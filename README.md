# resell-box-ai
AI project for detecting box defects
8-Week Project Schedule: ResellBox AI

Week 1-2: Define Project Scope and Criteria, Prepare Data Collection and Take Sample Images

* Finalize the project scope as close-up damage risk analysis, not full sneaker box inspection.
* Define the damage classes: normal, scratch, dent, tear, and stain.
* Define the risk levels: Low, Caution, and High.
* Separate the judging approach into numeric criteria mode and visual severity mode.
* Create the overall data collection and labeling plan.
* Prepare sneaker boxes, old shoe boxes, and general cardboard boxes.
* Create multiple damage examples on each box, such as scratches, dents, tears, and stains.
* Decide the close-up photo setup, including lighting, angle, and distance.
* Take a small set of sample images first.
* Check whether the labeling criteria are clear and realistic.

Week 3: Data Collection and Labeling

* Collect close-up images of damaged box areas.
* Aim to collect around 400 to 500 labeled images.
* Label each image with damage_type and risk_label.
* Use Label Studio or Google Sheets with a folder structure for labeling.
* Split the dataset into train, validation, and test sets.

Week 4: Model Preparation and Baseline Training

* Organize and preprocess the image dataset.
* Load a pretrained MobileNetV2 or MobileNetV3 model.
* Replace the final classification layer with five output classes: normal, scratch, dent, tear, and stain.
* Train a baseline damage classification model.
* Evaluate the model using validation accuracy and a confusion matrix.

Week 5: Model Improvement and OpenCV Features

* Apply data augmentation to improve model generalization.
* Check which damage classes the model struggles with.
* Add more data or adjust labels if needed.
* Build OpenCV-based image quality checks.

  * Blur detection
  * Brightness check
  * Damage visibility check
* Experiment with simple damage size or area estimation if needed.

Week 6: Risk Scoring and App Prototype

* Build a rule-based risk scoring system using model predictions and OpenCV outputs.
* Example: scratch + small area = Low, dent + medium area = Caution, tear + large area = High.
* Create a simple app interface where users can upload or take a close-up damage photo.
* Show the result screen with damage type, risk level, and a short explanation.

Week 7: Edge AI Deployment and System Integration

* Convert the trained model into a mobile-friendly format, such as TensorFlow Lite.
* Test on-device inference on the Galaxy S25.
* Connect the full workflow:

  * photo capture
  * OpenCV quality check
  * model prediction
  * risk scoring
  * result display
* Measure inference speed and usability.

Week 8: Final Testing, Evaluation, and Presentation

* Evaluate the final system using real sneaker box test images.
* Prepare sample outputs for Low, Caution, and High risk cases.
* Identify cases where the model works well and cases where it struggles.
* Discuss project limitations.

  * It does not replace official resale inspection.
  * It only analyzes close-up damage areas.
  * The dataset size is limited.
* Finalize the presentation slides and demo.
