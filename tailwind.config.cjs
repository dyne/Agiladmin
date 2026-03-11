module.exports = {
  content: [
    "./src/**/*.clj",
    "./resources/**/*.html",
    "./resources/**/*.js"
  ],
  theme: {
    extend: {}
  },
  plugins: [require("daisyui")],
  daisyui: {
    themes: ["nord", "dim"]
  }
};
