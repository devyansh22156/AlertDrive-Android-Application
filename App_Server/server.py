# app.py

import io, os
from collections import deque
from datetime import datetime

from flask import Flask, request, jsonify
from PIL import Image
import torch
import torch.nn as nn
from torchvision import transforms

# ----------------------
# Model Definition
# ----------------------
class DrowsinessModel(nn.Module):
    def __init__(self, feature_dim=1280, hidden_size=64):
        super().__init__()
        from torchvision import models
        base = models.efficientnet_b0(weights=models.EfficientNet_B0_Weights.DEFAULT)
        self.cnn = nn.Sequential(*list(base.children())[:-2])
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.rnn = nn.GRU(input_size=feature_dim, hidden_size=hidden_size, batch_first=True)
        self.classifier = nn.Sequential(
            nn.Linear(hidden_size, 64),
            nn.ReLU(),
            nn.Linear(64, 1),
            nn.Sigmoid()
        )

    def forward(self, x):
        B, T, C, H, W = x.shape
        x = x.view(B * T, C, H, W)
        x = self.cnn(x)
        x = self.pool(x).view(B, T, -1)
        _, h = self.rnn(x)
        return self.classifier(h[-1]).squeeze(1)

# ----------------------
# Flask & Globals
# ----------------------
app = Flask(__name__)

SEQ_LEN = 5
buffer = deque(maxlen=SEQ_LEN)

# smoothing counters and thresholds
drowsy_count = 0
non_drowsy_count = 0
HYSTERESIS_THRESH = 3  # need 3 consecutive windows to flip state
current_state = "Warming up"

DEVICE = torch.device("cuda" if torch.cuda.is_available() else "cpu")
model = DrowsinessModel().to(DEVICE)
model.load_state_dict(torch.load("drowsiness_model.pt", map_location=DEVICE))
model.eval()

transform = transforms.Compose([
    transforms.Resize((224, 224)),
    transforms.ToTensor(),
    transforms.Normalize([0.485, 0.456, 0.406],
                         [0.229, 0.224, 0.225])
])

DUMP_DIR = "received"
os.makedirs(DUMP_DIR, exist_ok=True)

# ----------------------
# Endpoint
# ----------------------
@app.route("/predict", methods=["POST"])
def predict():
    global drowsy_count, non_drowsy_count, current_state

    if "image" not in request.files:
        return jsonify(error="No image"), 400

    img = Image.open(io.BytesIO(request.files["image"].read())).convert("RGB")
    tensor = transform(img).unsqueeze(0)
    buffer.append(tensor)

    # warming up until full buffer
    if len(buffer) < SEQ_LEN:
        return jsonify(label="Warming up", confidence=0.0), 200

    # build sequence and predict
    seq = torch.cat(list(buffer), dim=0).unsqueeze(0).to(DEVICE)
    with torch.no_grad():
        p = model(seq).item()  # sigmoid output [0–1]

    # count smoothing
    if p > 0.5:
        drowsy_count += 1
        non_drowsy_count = 0
    else:
        non_drowsy_count += 1
        drowsy_count = 0

    # only flip state when a counter crosses threshold
    if drowsy_count >= HYSTERESIS_THRESH and current_state != "Drowsy":
        current_state = "Drowsy"
    elif non_drowsy_count >= HYSTERESIS_THRESH and current_state != "Non Drowsy":
        current_state = "Non Drowsy"

    # compute confidence relative to the current state
    confidence = p * 100 if current_state == "Drowsy" else (1 - p) * 100

    return jsonify(label=current_state, confidence=round(confidence,2)), 200

if __name__ == "__main__":
    app.run(host="192.168.29.140", port=5000)
