(function () {
  "use strict";

  const form = document.getElementById("graphicalmatrix-form");
  if (!form) {
    return;
  }

  const max = Number.parseInt(form.dataset.choiceCount || "", 10);
  const allowDuplicatesValue = form.dataset.allowDuplicates;
  const selectedInput = document.getElementById("selected");
  const status = document.getElementById("status");
  const submitButton = document.getElementById("submit-button");
  const resetButton = document.getElementById("reset-button");
  const selectedList = document.getElementById("selected-list");
  const selectedEmpty = document.getElementById("selected-empty");
  const tiles = Array.from(document.querySelectorAll(".tile[data-id]"));

  if (!Number.isInteger(max) || max < 1
      || !["true", "false"].includes(allowDuplicatesValue)
      || !selectedInput || !status || !submitButton || !resetButton
      || tiles.length === 0) {
    return;
  }

  const allowDuplicates = allowDuplicatesValue === "true";
  if (!allowDuplicates && max > tiles.length) {
    return;
  }

  const selected = [];

  function tileFor(id) {
    return tiles.find(function (tile) {
      return tile.dataset.id === id;
    });
  }

  function render() {
    tiles.forEach(function (tile) {
      const indexes = selected.map(function (id, index) {
        return id === tile.dataset.id ? index + 1 : null;
      }).filter(Boolean);
      const badge = tile.querySelector(".badge");
      if (!badge) {
        return;
      }
      if (indexes.length > 0) {
        tile.classList.add("selected");
        badge.textContent = allowDuplicates ? String(indexes.length) : String(indexes[0]);
      } else {
        tile.classList.remove("selected");
        badge.textContent = "";
      }
    });

    selectedInput.value = selected.join(",");
    status.textContent = selected.length + " / " + max + " 選択済み";
    submitButton.disabled = selected.length !== max;

    if (selectedList) {
      selectedList.replaceChildren();
      selected.forEach(function (id, index) {
        const tile = tileFor(id);
        const item = document.createElement("li");
        item.className = "selected-item";

        const order = document.createElement("span");
        order.className = "selected-order";
        order.textContent = String(index + 1);
        item.appendChild(order);

        if (tile) {
          const source = tile.querySelector("img");
          if (source) {
            const graphical = document.createElement("img");
            graphical.src = source.src;
            graphical.alt = "選択した画像";
            item.appendChild(graphical);
          }
        }

        item.addEventListener("click", function () {
          selected.splice(index, 1);
          render();
        });
        selectedList.appendChild(item);
      });
    }

    if (selectedEmpty) {
      selectedEmpty.hidden = selected.length > 0;
    }
  }

  tiles.forEach(function (tile) {
    tile.addEventListener("click", function () {
      const id = tile.dataset.id;
      if (!id) {
        return;
      }
      const index = selected.indexOf(id);
      if (allowDuplicates) {
        if (selected.length < max) {
          selected.push(id);
        }
      } else if (index >= 0) {
        selected.splice(index, 1);
      } else if (selected.length < max) {
        selected.push(id);
      }
      render();
    });
  });

  resetButton.addEventListener("click", function () {
    selected.length = 0;
    render();
  });

  form.addEventListener("submit", function (event) {
    if (selected.length !== max) {
      event.preventDefault();
      return;
    }
    const message = form.dataset.confirmMessage;
    if (message && !window.confirm(message)) {
      event.preventDefault();
    }
  });

  render();
}());
